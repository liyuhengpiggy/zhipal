package cn.seniorvoice.demo.nextaction.observation

import cn.seniorvoice.demo.nextaction.domain.UiSnapshot

/** Conservative Demo boundary. Text is never a complete detector; known sensitive apps are blocked too. */
object PrivacyFilter {
    private val packages = Regex("bank|wallet|alipay|unionpay|payment|authenticator|password|keyguard|permissioncontroller|packageinstaller|incallui|dialer|contacts|messaging|mms|medical|health", RegexOption.IGNORE_CASE)
    private val sensitive = Regex("密码|口令|验证码|身份证|银行卡|信用卡|支付|付款|收款码|转账|账户余额|人脸识别|指纹|病历|诊断|用药|password|passcode|one.time.code|credit.card|card.number|payment|checkout|sign.in|log.in|登录|登入|注册|授权|验证码", RegexOption.IGNORE_CASE)
    private val personal = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}|(?:\\+?\\d[\\s-]?){7,}|\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b|https?://", RegexOption.IGNORE_CASE)

    fun packageAllowed(packageName: String): Boolean = packageName.isNotBlank() &&
        packageName != "com.android.systemui" && !packages.containsMatchIn(packageName)

    fun reason(snapshot: UiSnapshot): String? = when {
        !packageAllowed(snapshot.packageName) -> "此应用属于敏感或系统界面，本次不采集"
        snapshot.nodes.isEmpty() -> "当前页面没有可读取的控件"
        snapshot.nodes.any { it.password } -> "页面包含密码控件，本次不采集"
        // Editable fields may contain names, addresses or draft messages even when unfocused.
        snapshot.nodes.any { it.editable && it.visible } -> "页面含输入框，本 Demo 不采集输入或草稿页面"
        snapshot.nodes.any { sensitive.containsMatchIn(it.label) || personal.containsMatchIn(it.label) } -> "页面检测到敏感或个人信息，本次不上传"
        else -> null
    }

    fun isSensitiveLabel(text: String): Boolean = sensitive.containsMatchIn(text) || personal.containsMatchIn(text)
}
