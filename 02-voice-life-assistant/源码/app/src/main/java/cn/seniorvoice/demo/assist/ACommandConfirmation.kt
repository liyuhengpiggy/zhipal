package cn.seniorvoice.demo.assist

import java.text.Normalizer
import java.util.Locale

/** An approval is a complete reply to one outstanding A offer, never a substring of a new task. */
object ACommandConfirmation {
    enum class Reply { ACCEPT, DECLINE, OTHER }
    fun matchesPrompt(expected: String, received: String?): Boolean = expected.isNotBlank() && expected == received
    fun reply(text: String): Reply {
        if (text.length > 100 || text.any { it.isISOControl() }) return Reply.OTHER
        val value = Normalizer.normalize(text, Normalizer.Form.NFKC).trim().lowercase(Locale.ROOT)
            .replace(Regex("[。.!！]+$"), "").trim()
        return when (value) {
            "确认", "确认执行", "执行", "好的", "好", "可以", "是", "是的", "yes", "confirm" -> Reply.ACCEPT
            "取消", "不要", "不用", "不", "不是", "不对", "算了", "停止", "取消操作", "no", "cancel" -> Reply.DECLINE
            else -> Reply.OTHER
        }
    }
}
