package cn.seniorvoice.demo.nextaction.execution

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityNodeInfo
import cn.seniorvoice.demo.nextaction.domain.*
import cn.seniorvoice.demo.nextaction.observation.AccessibilityBridge
import cn.seniorvoice.demo.nextaction.observation.NextActionAccessibilityService
import cn.seniorvoice.demo.nextaction.observation.PrivacyFilter

/** The only place in this Feature allowed to call Android action APIs. No fallback and no retry. */
class AccessibilityActionExecutor(private val service: NextActionAccessibilityService,
    private val bridge: AccessibilityBridge) : ActionExecutor {
    private val policy = ActionPolicy()
    private val resolver = TargetResolver()

    override fun execute(request: ExecutionRequest): ExecutionResult = bridge.onMain {
        if (!service.collectionAllowed() || service.isDeviceLocked() || !request.permit.isValid)
            return@onMain ExecutionResult(false, false, "会话关闭、锁屏或请求已过期")
        val current = bridge.currentSnapshot()
        if (!resolver.isSamePage(request.originalSnapshot, current) || current.revision != request.originalSnapshot.revision ||
            PrivacyFilter.reason(current) != null || !policy.allows(request.candidate, current))
            return@onMain ExecutionResult(false, false, "页面或本地风险校验未通过，未执行")
        val candidate = request.candidate
        if (candidate.action == ActionType.NO_OP) return@onMain ExecutionResult(false, true, "NO_OP")
        if (candidate.action == ActionType.BACK) {
            if (!request.permit.claim()) return@onMain ExecutionResult(false, false, "请求取消或已执行过")
            val accepted = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            return@onMain ExecutionResult(true, accepted, if (accepted) "已请求一次返回；本次结束" else "系统未接受返回；本次结束，不重试")
        }
        val target = resolver.resolve(candidate, current)
            ?: return@onMain ExecutionResult(false, false, "目标已消失或不能唯一定位，未执行")
        bridge.withFreshRoot { holder ->
            if (holder.root.packageName?.toString() != current.packageName || holder.root.windowId != current.windowId ||
                service.revision.get() != current.revision || !request.permit.isValid)
                return@withFreshRoot ExecutionResult(false, false, "执行前窗口变化，未执行")
            val node = holder.parser.find(holder.root, target.nodeKey)
                ?: return@withFreshRoot ExecutionResult(false, false, "目标控件已消失")
            try {
                if (!node.refresh() || !node.isEnabled || !node.isVisibleToUser || node.isPassword || node.isEditable)
                    return@withFreshRoot ExecutionResult(false, false, "目标状态变化，未执行")
                val freshBounds = android.graphics.Rect().also(node::getBoundsInScreen)
                val freshLabel = (node.contentDescription?.toString()?.takeIf { it.isNotBlank() } ?: node.text?.toString().orEmpty()).take(160)
                if (node.packageName?.toString() != current.packageName || node.windowId != current.windowId ||
                    node.className?.toString() != target.className || node.viewIdResourceName != target.viewId ||
                    freshLabel != target.label || Bounds(freshBounds.left, freshBounds.top, freshBounds.right, freshBounds.bottom) != target.bounds ||
                    service.revision.get() != current.revision || PrivacyFilter.isSensitiveLabel(freshLabel))
                    return@withFreshRoot ExecutionResult(false, false, "执行前目标特征发生变化，未执行")
                when (candidate.action) {
                    ActionType.TAP -> {
                        if (!node.isClickable) return@withFreshRoot ExecutionResult(false, false, "目标不可点击")
                        if (!request.permit.claim()) return@withFreshRoot ExecutionResult(false, false, "请求已取消或已执行过")
                        val accepted = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        ExecutionResult(true, accepted, if (accepted) "已提交一次节点点击；本次结束" else "节点未接受点击；本次结束，不回退坐标")
                    }
                    ActionType.SCROLL_UP, ActionType.SCROLL_DOWN -> {
                        if (!node.isScrollable) return@withFreshRoot ExecutionResult(false, false, "目标不可滚动")
                        val b = target.bounds
                        val left = b.left.coerceAtLeast(0); val right = b.right.coerceAtMost(current.screenWidth)
                        val top = b.top.coerceAtLeast(0); val bottom = b.bottom.coerceAtMost(current.screenHeight)
                        if (right - left < 24 || bottom - top < 80) return@withFreshRoot ExecutionResult(false, false, "滚动区域太小")
                        val x = (left + right) / 2f
                        val high = top + (bottom - top) * 0.25f; val low = top + (bottom - top) * 0.75f
                        val path = Path().apply {
                            if (candidate.action == ActionType.SCROLL_DOWN) { moveTo(x, low); lineTo(x, high) }
                            else { moveTo(x, high); lineTo(x, low) }
                        }
                        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 320)).build()
                        if (!request.permit.claim()) return@withFreshRoot ExecutionResult(false, false, "请求已取消或已执行过")
                        val accepted = service.dispatchGesture(gesture, null, null)
                        ExecutionResult(true, accepted, if (accepted) "已提交一次滚动；未验证最终位置，本次结束" else "系统未接受滚动；本次结束，不重试")
                    }
                    else -> ExecutionResult(false, false, "不支持此动作")
                }
            } finally { @Suppress("DEPRECATION") node.recycle() }
        }
    }
}
