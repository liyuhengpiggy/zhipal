package cn.seniorvoice.demo.assist

/** The B demo only assists with ordinary navigation; consequential actions stay manual. */
object BAgentActionPolicy {
    private val sensitive = Regex("支付|付款|转账|汇款|购买|下单|删除|卸载|发送|提交|授权|同意|确认交易|pay|purchase|delete|send|submit|authorize", RegexOption.IGNORE_CASE)
    fun allowed(text: String, description: String, viewId: String) =
        (text.isNotBlank() || description.isNotBlank()) && !sensitive.containsMatchIn(text + description + viewId)
}
