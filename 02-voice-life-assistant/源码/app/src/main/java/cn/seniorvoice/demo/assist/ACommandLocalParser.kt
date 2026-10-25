package cn.seniorvoice.demo.assist

import java.text.Normalizer
import java.util.Locale

/** Only complete, explicit single instructions qualify. Uncertain language remains A's AI input. */
object ACommandLocalParser {
    fun parse(text: String, currentPackage: String? = null, controls: List<ACommandControl> = emptyList()): ACommandIntent? {
        val command = normalize(text) ?: return null
        when (command) {
            "返回", "返回上一页", "回到上一页", "返回上一级", "后退", "back", "go back" ->
                return ACommandIntent.Navigate(ACommandNavigation.BACK)
            "桌面", "回桌面", "回到桌面", "返回桌面", "回到主屏幕", "返回主屏幕", "显示主屏幕", "home", "go home", "show home screen" ->
                return ACommandIntent.Navigate(ACommandNavigation.HOME)
            "最近任务", "最近应用", "打开最近任务", "显示最近任务", "打开最近应用", "显示最近应用", "打开多任务", "显示多任务", "recents", "open recents", "show recent apps" ->
                return ACommandIntent.Navigate(ACommandNavigation.RECENTS)
            "关闭当前应用", "退出当前应用", "关闭当前软件", "退出当前软件", "关闭这个应用", "退出这个应用", "close current app", "exit current app" ->
                return currentPackage?.takeIf(ACommandProtocol::validPackage)?.let(ACommandIntent::ExitApp)
        }
        val action = scrollAction(text) ?: return null
        if (controls.size > ACommandProtocol.MAX_CONTROLS || controls.map { it.id }.distinct().size != controls.size) return null
        val target = controls.singleOrNull { action in it.actions } ?: return null
        return ACommandIntent.ScreenAction(target.id, action)
    }

    /** No target invented here: the host must bind this direction to a current real scrollable node. */
    fun scrollAction(text: String): ACommandAction? = when (normalize(text)) {
        "向下翻页", "往下翻页", "向下滚动", "往下滚动", "向下滑动", "往下滑动", "下一屏", "scroll down", "page down" -> ACommandAction.SCROLL_FORWARD
        "向上翻页", "往上翻页", "向上滚动", "往上滚动", "向上滑动", "往上滑动", "上一屏", "scroll up", "page up" -> ACommandAction.SCROLL_BACKWARD
        else -> null
    }

    private fun normalize(raw: String): String? {
        if (raw.length > 160 || raw.any { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }) return null
        var value = Normalizer.normalize(raw, Normalizer.Form.NFKC).trim().lowercase(Locale.ROOT)
            .replace(Regex("[。.!！]+$"), "").trim()
        value = value.replace(Regex("^(?:请帮我|麻烦你|帮我|请)"), "").trim()
            .replace(Regex("^please\\s+"), "")
        return value.takeIf { it.isNotBlank() }
    }
}
