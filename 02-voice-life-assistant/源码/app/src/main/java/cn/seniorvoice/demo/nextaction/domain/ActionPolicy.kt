package cn.seniorvoice.demo.nextaction.domain

/** Conservative demo policy. Confidence never replaces these checks. */
class ActionPolicy {
    private val sensitive = Regex(
        "密码|口令|验证码|支付|付款|转账|汇款|银行|信用卡|收款|充值|提现|交易|订单|购买|买单|下单|订购|结算|" +
            "发送|发布|提交|确认|确定|删除|清空|移除|卸载|格式化|拨号|拨打|呼叫|电话|通话|登录|登陆|注册|授权|允许|同意|分享|上传|保存|安装|下载|" +
            "password|passcode|verification|credit.?card|payment|purchase|checkout|transfer|send|publish|submit|confirm|delete|remove|buy|call|sign.?in|log.?in|authorize",
        RegexOption.IGNORE_CASE
    )
    private val sensitivePackage = Regex(
        "(?:bank|banking|wallet|payment|incallui|dialer|contacts|permissioncontroller|packageinstaller)",
        RegexOption.IGNORE_CASE
    )
    private val privateScreenText = Regex("密码|口令|验证码|支付|付款|转账|汇款|银行卡|信用卡|身份证|password|passcode|verification|credit.?card|payment", RegexOption.IGNORE_CASE)
    private val safeNavigation = setOf(
        "下一页", "上一页", "更多", "更多选项", "展开", "展开详情", "收起", "返回", "返回上一页", "菜单", "主页", "首页", "测试下一页",
        "nextpage", "previouspage", "more", "expand", "collapse", "back", "menu", "home", "testnextpage"
    )

    private fun normalized(text: String) = text.trim().lowercase().replace(Regex("\\s+"), "")
    fun isSensitive(text: String) = sensitive.containsMatchIn(text)

    fun allowsObservation(snapshot: UiSnapshot): Boolean =
        snapshot.packageName.isNotBlank() && !sensitivePackage.containsMatchIn(snapshot.packageName) &&
            snapshot.nodes.none { it.visible && (it.password || (it.editable && it.label.isNotBlank()) || privateScreenText.containsMatchIn(it.label)) }

    fun allowsNode(node: UiNodeSnapshot): Boolean =
        node.visible && node.enabled && node.clickable && !node.password && !node.editable &&
            !node.className.contains("EditText", ignoreCase = true) && node.nodeKey.isNotBlank() &&
            node.className.isNotBlank() && node.bounds.isValid && node.label.length in 1..160 &&
            !isSensitive(node.label) && normalized(node.label) in safeNavigation

    fun allows(candidate: Candidate, snapshot: UiSnapshot): Boolean {
        if (candidate.action == ActionType.NO_OP) return true
        if (!allowsObservation(snapshot)) return false
        if (candidate.action == ActionType.BACK) return candidate.nodeKey == null && candidate.bounds == null
        val node = TargetResolver().resolve(candidate, snapshot) ?: return false
        if (!node.bounds.fits(snapshot.screenWidth, snapshot.screenHeight)) return false
        return when (candidate.action) {
            ActionType.TAP -> allowsNode(node)
            ActionType.SCROLL_UP, ActionType.SCROLL_DOWN -> node.visible && node.enabled && node.scrollable &&
                !node.password && !node.editable && !node.className.contains("EditText", ignoreCase = true) && !isSensitive(node.label)
            else -> false
        }
    }
}
