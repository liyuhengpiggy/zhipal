package cn.seniorvoice.demo.assist

import java.text.Normalizer

/** Only a complete reply to the outstanding A question may authorize its saved candidate. */
object ContactCallConfirmation {
    sealed class Reply {
        data class Select(val index: Int) : Reply()
        object Decline : Reply()
        object Unclear : Reply()
    }

    fun reply(text: String, names: List<String>): Reply {
        val value = normalize(text)
        if (value in setOf("不", "不是", "不对", "不要", "不用", "取消", "算了", "别打", "不要打", "不要打电话", "no", "cancel"))
            return Reply.Decline
        if (names.size == 1 && value in setOf("是", "是的", "对", "对的", "确认", "确认拨打", "可以", "好的", "好", "拨打", "yes", "confirm"))
            return Reply.Select(0)
        val index = listOf("第一个", "第二个", "第三个", "第四个", "第五个").indexOf(value)
        if (index in names.indices) return Reply.Select(index)
        val exact = names.indices.filter { normalize(names[it]) == value }
        return if (exact.size == 1) Reply.Select(exact.single()) else Reply.Unclear
    }

    private fun normalize(text: String) = Normalizer.normalize(text, Normalizer.Form.NFKC)
        .trim().lowercase(java.util.Locale.ROOT).replace(Regex("[。.!！]+$"), "")
        .replace(Regex("[\\s\\p{Z}\\p{Cf}]+"), "")
}
