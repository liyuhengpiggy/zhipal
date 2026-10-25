package cn.seniorvoice.demo.assist

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import cn.seniorvoice.demo.nextaction.domain.UiSnapshot
import cn.seniorvoice.demo.nextaction.observation.NextActionAccessibilityService
import cn.seniorvoice.demo.nextaction.observation.PrivacyFilter
import cn.seniorvoice.demo.nextaction.observation.UiTreeParser

/** A owns these snapshots. They never enter B's session or its execution controller. */
class ACommandScreenController(private val service: NextActionAccessibilityService) {
    data class Captured(val snapshot: UiSnapshot, val screen: ACommandScreen)
    private val parser = UiTreeParser()

    fun currentPackage(): String? {
        val root = service.rootInActiveWindow ?: return null
        return try {
            root.packageName?.toString()?.takeIf { service.safeActiveWindow(root.windowId) && !service.isDeviceLocked() }
        } finally { @Suppress("DEPRECATION") root.recycle() }
    }

    fun capture(): Captured? = runCatching {
        check(AssistRuntime.aScreenAllowed && !service.isDeviceLocked())
        val root = service.rootInActiveWindow ?: return null
        val snapshot = try {
            check(root.packageName?.toString() != service.packageName && service.safeActiveWindow(root.windowId))
            parser.parse(root, service.revision.get(), SystemClock.elapsedRealtime(),
                service.resources.displayMetrics.widthPixels, service.resources.displayMetrics.heightPixels)
        } finally { @Suppress("DEPRECATION") root.recycle() }
        check(PrivacyFilter.reason(snapshot) == null)
        val controls = snapshot.nodes.filter {
            it.visible && it.enabled && !it.password && !it.editable && it.bounds.fits(snapshot.screenWidth, snapshot.screenHeight) &&
                (it.clickable || it.scrollable) && !sensitive(it.label) &&
                it.label.none { char -> char.isISOControl() || Character.getType(char) == Character.FORMAT.toInt() }
        }.mapNotNull { node ->
            val actions = buildList {
                if (node.clickable && node.label.isNotBlank()) add(ACommandAction.CLICK)
                if (node.scrollable) { add(ACommandAction.SCROLL_FORWARD); add(ACommandAction.SCROLL_BACKWARD) }
            }
            if (actions.isEmpty()) null else ACommandControl(node.nodeKey,
                node.label.ifBlank { "页面滚动区域" }, actions)
        }.take(80)
        Captured(snapshot, ACommandScreen(snapshot.packageName, controls))
    }.getOrNull()

    /** Called after the confirmation window has closed. No coordinates, retries or chained steps. */
    fun execute(captured: Captured, intent: ACommandIntent.ScreenAction, gate: ACommandExecutionGate): Pair<Boolean, String> {
        val fresh = capture() ?: return false to "当前页面不能读取，已停止，没有执行"
        if (fresh.snapshot.uiFingerprint != captured.snapshot.uiFingerprint || fresh.screen != captured.screen)
            return false to "页面已经变化，旧操作已取消，请重新发起 A 指令"
        val control = captured.screen.controls.singleOrNull { it.id == intent.target }
            ?: return false to "目标控件已不存在，没有执行"
        if (intent.action !in control.actions) return false to "目标不支持这一步操作，没有执行"
        val expected = captured.snapshot.nodes.singleOrNull { it.nodeKey == control.id }
            ?: return false to "目标控件无法唯一定位，没有执行"
        val root = service.rootInActiveWindow ?: return false to "当前页面不可用，没有执行"
        try {
            if (service.isDeviceLocked() || !AssistRuntime.aScreenAllowed || !service.safeActiveWindow(root.windowId) ||
                root.packageName?.toString() != captured.snapshot.packageName || root.windowId != captured.snapshot.windowId)
                return false to "执行前页面或权限已变化，没有执行"
            val node = parser.find(root, control.id) ?: return false to "目标控件已消失，没有执行"
            try {
                if (!node.refresh()) return false to "执行前控件已变化，没有执行"
                val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
                val label = (node.contentDescription?.toString()?.takeIf { it.isNotBlank() } ?: node.text?.toString().orEmpty()).take(160)
                if (!node.isVisibleToUser || !node.isEnabled || node.isPassword || node.isEditable ||
                    node.packageName?.toString() != captured.snapshot.packageName || node.windowId != captured.snapshot.windowId ||
                    node.className?.toString() != expected.className || node.viewIdResourceName != expected.viewId ||
                    label != expected.label || bounds.left != expected.bounds.left || bounds.top != expected.bounds.top ||
                    bounds.right != expected.bounds.right || bounds.bottom != expected.bounds.bottom || sensitive(label))
                    return false to "执行前控件已变化，没有执行"
                val action = when (intent.action) {
                    ACommandAction.CLICK -> if (node.isClickable) AccessibilityNodeInfo.ACTION_CLICK else return false to "目标不可点击"
                    ACommandAction.SCROLL_FORWARD -> if (node.isScrollable) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else return false to "目标不可翻页"
                    ACommandAction.SCROLL_BACKWARD -> if (node.isScrollable) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else return false to "目标不可翻页"
                }
                if (!gate.claim(SystemClock.elapsedRealtime(), fresh.snapshot.uiFingerprint)) return false to "确认已过期或已处理，没有重复执行"
                val accepted = node.performAction(action)
                return accepted to if (accepted) "已提交一次${if (intent.action == ACommandAction.CLICK) "页面跳转" else "翻页"}，本次结束，请核对手机页面"
                    else "当前应用未接受这一步操作，已停止"
            } finally { @Suppress("DEPRECATION") node.recycle() }
        } finally { @Suppress("DEPRECATION") root.recycle() }
    }

    /** Read-only post-action check. A result is never inferred from performAction(true) alone. */
    fun verifyChanged(captured: Captured): String {
        val fresh = capture() ?: return "系统已接受一次操作，但当前页面无法读取；本次已停止，请核对手机页面"
        return if (fresh.snapshot.packageName != captured.snapshot.packageName ||
            fresh.snapshot.uiFingerprint != captured.snapshot.uiFingerprint) {
            "已执行一次操作并检测到页面变化，本次结束"
        } else {
            "系统已接受一次操作，但页面暂未出现可见变化；本次已停止，请核对手机页面"
        }
    }

    companion object {
        private val sideEffects = Regex("发送|发布|提交|确认|确定|删除|清空|移除|卸载|格式化|购买|买单|下单|订购|充值|提现|交易|支付|付款|转账|汇款|拨号|拨打|呼叫|登录|登陆|注册|授权|允许|同意|分享|上传|保存|安装|下载|send|publish|submit|confirm|delete|remove|buy|purchase|payment|checkout|transfer|call|sign.?in|log.?in|authorize", RegexOption.IGNORE_CASE)
        fun sensitive(label: String) = PrivacyFilter.isSensitiveLabel(label) || sideEffects.containsMatchIn(label)
    }
}
