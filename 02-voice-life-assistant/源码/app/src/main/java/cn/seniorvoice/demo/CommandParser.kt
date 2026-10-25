package cn.seniorvoice.demo

import java.text.Normalizer

/** Only one explicit, complete instruction can launch an external activity. */
object CommandParser {
    sealed class Command {
        /** Resolved against Android's real installed-app labels, never a per-app command list. */
        data class OpenApp(val name: String) : Command()
        data class Call(val name: String) : Command()
        object Unknown : Command()
    }

    private val politePrefix = Regex("^(?:请帮我|麻烦你|帮我|请)")
    private val openPattern = Regex("(?:打开|启动|进入)(?:一下)?(.{1,60})")
    private val phonePatterns = listOf(
        "给(.{1,20}?)打(?:个)?电话", "拨打(.{1,20}?)的电话", "拨打(.{1,20}?)电话",
        "打电话给(.{1,20})", "呼叫(.{1,20})"
    ).map(::Regex)
    private val clauseSeparator = Regex("[，,；;\\r\\n。！？!?]")
    // App names may themselves contain action words, such as “搜狗搜索” or “启动器”.
    // An OpenApp only resolves the entire remaining name against real launcher labels; it
    // never interprets a suffix as a search, tap, message, or other action inside an app.
    private val openCompoundInstruction = Regex(
        "然后|接着|接下来|并且|顺便|同时|之后|以后|" +
            "再(?:给|打开|启动|进入|拨打|呼叫|帮我)|并(?:给|打开|启动|进入)|" +
            "发(?:送)?(?:一?(?:个|条))?(?:消息|信息|短信)|打(?:个)?(?:电话|视频)|" +
            "(?:给|向).{1,20}(?:打(?:个)?(?:电话|视频)|发(?:送)?(?:一?(?:个|条))?(?:消息|信息|短信))|" +
            "(?:帮我|帮忙)(?:打开|启动|进入|搜索|点击|发送|拨打|呼叫|输入|转账|付款)"
    )
    // Contact matching remains deliberately stricter than launcher-label matching.
    private val compoundInstruction = Regex(
        "然后|接着|接下来|并且|顺便|同时|之后|以后|" +
            "再(?:给|打开|启动|进入|拨打|呼叫|帮我)|并(?:给|打开|启动|进入)|" +
            "发送|发消息|发信息|发短信|打(?:个)?电话|打(?:个)?视频|拨打|呼叫|" +
            "(?:给|向).{0,20}(?:打|发)|输入密码|转账|付款|帮我|帮忙|搜索|点击"
    )
    private val vagueTarget = Regex("^(?:一个|某个|某些|那个|这个|所有|全部|随便|我想|我要|我需要|你帮)")

    fun parse(raw: String): Command {
        if (raw.length > 160) return Command.Unknown
        val sentence = Normalizer.normalize(raw, Normalizer.Form.NFKC).trim()
            .replace(Regex("[。.!！]+$"), "")
        // Do not erase clause punctuation and accidentally turn several commands into one name.
        if (clauseSeparator.containsMatchIn(sentence)) return Command.Unknown
        // Firmware text v1 currently uses en. Accept only an explicit single instruction;
        // resolve the complete target against real launcher labels / saved contacts as usual.
        val english = sentence.replace(Regex("^please\\s+", RegexOption.IGNORE_CASE), "")
        Regex("(open|launch|start|call)\\s+(.{1,60})", RegexOption.IGNORE_CASE).matchEntire(english)?.let {
            val target = it.groupValues[2].trim()
            if (target.isBlank() || Regex("\\b(?:and|or|then|after|before|please|don't|do not)\\b", RegexOption.IGNORE_CASE).containsMatchIn(target))
                return Command.Unknown
            return if (it.groupValues[1].equals("call", true)) Command.Call(target) else Command.OpenApp(target)
        }
        val text = sentence.replace(Regex("[\\s\\p{Z}\\p{Cf}]+"), "")
            .replace(politePrefix, "")

        openPattern.matchEntire(text)?.let {
            val name = it.groupValues[1]
            if (openCompoundInstruction.containsMatchIn(name) || vagueTarget.containsMatchIn(name) ||
                name.startsWith("不要") || name.startsWith("别") ||
                Regex("(?:吗|么|如何|怎么|什么)$").containsMatchIn(name)
            ) return Command.Unknown
            // Retain polite endings until label matching. Otherwise “打开贴吧” becomes “打开贴”.
            return Command.OpenApp(name)
        }

        for (pattern in phonePatterns) {
            pattern.matchEntire(text)?.let {
                val name = it.groupValues[1]
                if (compoundInstruction.containsMatchIn(name) ||
                    Regex("(?:打开|启动|进入|不要|别)").containsMatchIn(name)
                ) return Command.Unknown
                return Command.Call(name)
            }
        }
        return Command.Unknown
    }
}
