package cn.seniorvoice.demo.assist

object BAgentConfig {
    fun validate(url: String, model: String, token: String, lan: Boolean, debug: Boolean): String {
        val uri = OcrProtocol.endpoint(url, lan, debug)
        require(model.trim().isNotBlank() && model.length <= 200 && model.none { it.isISOControl() }) { "请填写视觉模型名称" }
        require(token.none { it.isISOControl() }) { "模型令牌不能包含换行或控制字符" }
        return uri.toString().trimEnd('/')
    }
}
