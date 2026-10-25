package cn.seniorvoice.demo.assist

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** Strict command contract; this parser produces data and has no Android execution capabilities. */
object ACommandProtocol {
    const val MAX_APPS = 500
    const val MAX_CONTACTS = 100
    const val MAX_CONTROLS = 80
    const val MAX_REQUEST_BYTES = 128 * 1024
    const val MAX_RESPONSE_BYTES = 64 * 1024
    private const val ROUTE = "/v1/commands/interpret"
    private val idPattern = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,159}")
    private val packagePattern = Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*")

    /** A configured root, /v1, or B completion endpoint resolves to the same command endpoint. */
    fun endpoint(value: String, allowLanHttp: Boolean = false, debugBuild: Boolean = false): URI {
        val uri = try { URI(value.trim()) } catch (_: Exception) { invalidEndpoint() }
        val host = uri.host.orEmpty()
        val parts = host.split('.').map { it.toIntOrNull() }
        val privateV4 = parts.size == 4 && parts.all { it != null && it in 0..255 } &&
            (parts[0] == 10 || parts[0] == 127 || parts[0] == 192 && parts[1] == 168 ||
                parts[0] == 172 && parts[1]!! in 16..31)
        val path = uri.rawPath.orEmpty().trimEnd('/')
        require(host.isNotEmpty() && uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            uri.port in -1..65535 && uri.port != 0 && uri.normalize() == uri &&
            (uri.scheme == "https" || uri.scheme == "http" && allowLanHttp && debugBuild && privateV4) &&
            path in setOf("", "/v1", "/v1/chat/completions", "/chat/completions", ROUTE)) {
            "请填写 HTTPS 服务器根地址或 B 代理地址；仅调试版显式允许内网 IPv4 HTTP"
        }
        return URI(uri.scheme, null, uri.host, uri.port, ROUTE, null, null)
    }

    private fun invalidEndpoint(): Nothing = throw IllegalArgumentException("A 指令服务地址格式错误")

    fun validateToken(token: String) {
        require(token.isNotBlank() && token.length <= 4096 && token.all { it in '\u0021'..'\u007e' } &&
            !token.startsWith("sk-", ignoreCase = true)) {
            "请填写自有服务器访问令牌，不能填写第三方 API Key 或包含空白字符"
        }
    }

    /** Freeze caller-owned lists before starting HTTP, so targets cannot change during inference. */
    fun snapshot(request: ACommandRequest): ACommandRequest = request.copy(
        apps = request.apps.map { it.copy() }, contacts = request.contacts.map { it.copy() },
        screen = request.screen?.let { screen -> screen.copy(controls = screen.controls.map { it.copy(actions = it.actions.toList()) }) }
    ).also(::validateRequest)

    fun validateRequest(request: ACommandRequest) {
        require(request.version == 1 && idPattern.matches(request.taskId)) { "A 指令任务版本或编号无效" }
        require(validText(request.utterance, 2000)) { "A 指令内容为空、过长或含控制字符" }
        require(request.apps.size <= MAX_APPS && request.contacts.size <= MAX_CONTACTS) { "A 指令目标数量超过限制" }
        require(request.apps.map { it.id }.distinct().size == request.apps.size &&
            request.contacts.map { it.id }.distinct().size == request.contacts.size) { "A 指令目标编号重复" }
        request.apps.forEach { require(validPackage(it.id) && validText(it.label, 200)) { "A 应用目标无效" } }
        request.contacts.forEach { require(idPattern.matches(it.id) && validText(it.name, 200)) { "A 联系人目标无效" } }
        request.screen?.let { screen ->
            require(validPackage(screen.packageName) && screen.controls.size <= MAX_CONTROLS &&
                screen.controls.map { it.id }.distinct().size == screen.controls.size) { "A 当前页面快照无效" }
            screen.controls.forEach {
                require(idPattern.matches(it.id) && validText(it.label, 200) && it.actions.isNotEmpty() &&
                    it.actions.size <= ACommandAction.entries.size && it.actions.distinct().size == it.actions.size) { "A 页面控件无效" }
            }
        }
    }

    /** Keep the original question and page intact; only trim the app directory to fit the proxy. */
    fun fitForProxy(request: ACommandRequest): ACommandRequest {
        var fitted = snapshot(request)
        while (serialize(fitted).toByteArray(Charsets.UTF_8).size > 120 * 1024) {
            require(fitted.apps.isNotEmpty()) { "本次页面和联系人信息过多，请缩小范围后重新发起指令" }
            fitted = fitted.copy(apps = fitted.apps.take((fitted.apps.size * 3 / 4).coerceAtMost(fitted.apps.size - 1)))
        }
        return fitted
    }

    fun encode(request: ACommandRequest): String {
        validateRequest(request)
        return serialize(request).also {
            require(it.toByteArray(Charsets.UTF_8).size <= MAX_REQUEST_BYTES) { "A 指令请求过大" }
        }
    }

    private fun serialize(request: ACommandRequest): String {
        val apps = JSONArray(); request.apps.forEach { apps.put(JSONObject().put("id", it.id).put("label", it.label)) }
        val contacts = JSONArray(); request.contacts.forEach { contacts.put(JSONObject().put("id", it.id).put("name", it.name)) }
        val screen = request.screen?.let {
            val controls = JSONArray()
            it.controls.forEach { control ->
                val actions = JSONArray(); control.actions.forEach { action -> actions.put(action.wire) }
                controls.put(JSONObject().put("id", control.id).put("label", control.label).put("actions", actions))
            }
            JSONObject().put("package_name", it.packageName).put("controls", controls)
        }
        return JSONObject().put("version", request.version).put("task_id", request.taskId).put("utterance", request.utterance)
            .put("apps", apps).put("contacts", contacts).put("screen", screen ?: JSONObject.NULL).toString()
    }

    fun parseResponse(body: String, request: ACommandRequest): ACommandResponse {
        validateRequest(request)
        require(body.length <= MAX_RESPONSE_BYTES && body.toByteArray(Charsets.UTF_8).size <= MAX_RESPONSE_BYTES) { "A 指令响应过大" }
        val root = StrictJson(body).read() as? Map<*, *> ?: invalidResponse()
        keys(root, "version", "task_id", "intent")
        require((root["version"] as? JsonNumber)?.raw == "1" && string(root, "task_id") == request.taskId) {
            "A 指令响应与本次任务或版本不匹配"
        }
        val intent = root["intent"] as? Map<*, *> ?: invalidResponse()
        val parsed = when (string(intent, "type")) {
            "open_app" -> {
                keys(intent, "type", "target")
                val target = string(intent, "target")
                require(request.apps.any { it.id == target }) { "A 指令应用不在本次真实目标中" }
                ACommandIntent.OpenApp(target)
            }
            "call_contact" -> {
                keys(intent, "type", "target")
                val target = string(intent, "target")
                require(request.contacts.any { it.id == target }) { "A 指令联系人不在本次真实目标中" }
                ACommandIntent.CallContact(target)
            }
            "navigate" -> {
                keys(intent, "type", "target")
                val target = ACommandNavigation.entries.singleOrNull { it.wire == string(intent, "target") } ?: invalidResponse()
                ACommandIntent.Navigate(target)
            }
            "exit_app" -> {
                keys(intent, "type", "target")
                val target = string(intent, "target")
                require(request.screen != null && target == request.screen.packageName) { "A 指令退出目标不是本次前台应用" }
                ACommandIntent.ExitApp(target)
            }
            "screen_action" -> {
                keys(intent, "type", "target", "action")
                val target = string(intent, "target")
                val action = ACommandAction.entries.singleOrNull { it.wire == string(intent, "action") } ?: invalidResponse()
                val control = request.screen?.controls?.singleOrNull { it.id == target }
                require(control != null && action in control.actions) { "A 指令控件或动作不在本次真实页面中" }
                ACommandIntent.ScreenAction(target, action)
            }
            "vision" -> {
                keys(intent, "type", "mode")
                val mode = ACommandVisionMode.entries.singleOrNull { it.name == string(intent, "mode") } ?: invalidResponse()
                ACommandIntent.Vision(mode)
            }
            "clarify", "unsupported" -> {
                keys(intent, "type", "message")
                val message = string(intent, "message")
                require(validText(message, 600)) { "A 指令说明为空、过长或含控制字符" }
                if (string(intent, "type") == "clarify") ACommandIntent.Clarify(message) else ACommandIntent.Unsupported(message)
            }
            else -> invalidResponse()
        }
        return ACommandResponse(request.taskId, parsed)
    }

    private fun keys(value: Map<*, *>, vararg expected: String) {
        require(value.keys == expected.toSet()) { "A 指令响应字段不符合协议" }
    }
    private fun string(value: Map<*, *>, key: String): String = value[key] as? String ?: invalidResponse()
    internal fun validPackage(value: String) = value.length <= 255 && packagePattern.matches(value)
    private fun validText(value: String, limit: Int) = value.isNotBlank() && value.length <= limit &&
        value.none { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() } && validUnicode(value)
    private fun invalidResponse(): Nothing = throw IllegalArgumentException("A 指令响应格式无效")

    private fun validUnicode(value: String): Boolean {
        var position = 0
        while (position < value.length) {
            val ch = value[position++]
            if (ch.isHighSurrogate()) {
                if (position == value.length || !value[position++].isLowSurrogate()) return false
            } else if (ch.isLowSurrogate()) return false
        }
        return true
    }

    private data class JsonNumber(val raw: String)

    /** Android org.json is permissive and overwrites duplicate keys; reject both behaviours here. */
    private class StrictJson(private val input: String) {
        private var position = 0
        private var nodes = 0
        fun read(): Any? {
            val value = value(0); whitespace()
            if (position != input.length) invalidResponse()
            return value
        }
        private fun value(depth: Int): Any? {
            if (depth > 8 || ++nodes > 1024) invalidResponse()
            whitespace()
            if (position >= input.length) invalidResponse()
            return when (input[position]) {
                '{' -> {
                    position++; whitespace()
                    val result = linkedMapOf<String, Any?>()
                    if (consume('}')) result else {
                        while (true) {
                            whitespace(); val key = string()
                            if (result.containsKey(key)) invalidResponse()
                            whitespace(); expect(':'); result[key] = value(depth + 1); whitespace()
                            if (consume('}')) break
                            expect(',')
                        }
                        result
                    }
                }
                '[' -> {
                    position++; whitespace()
                    val result = mutableListOf<Any?>()
                    if (consume(']')) result else {
                        while (true) {
                            result.add(value(depth + 1)); whitespace()
                            if (consume(']')) break
                            expect(',')
                        }
                        result
                    }
                }
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                '-', in '0'..'9' -> number()
                else -> invalidResponse()
            }
        }
        private fun string(): String {
            expect('"'); val result = StringBuilder()
            while (position < input.length) {
                val ch = input[position++]
                if (ch == '"') return result.toString().also { if (!validUnicode(it)) invalidResponse() }
                if (ch < ' ') invalidResponse()
                if (ch != '\\') { result.append(ch); continue }
                if (position >= input.length) invalidResponse()
                result.append(when (val escape = input[position++]) {
                    '"', '\\', '/' -> escape
                    'b' -> '\b'
                    'f' -> '\u000c'
                    'n' -> '\n'
                    'r' -> '\r'
                    't' -> '\t'
                    'u' -> {
                        if (position + 4 > input.length) invalidResponse()
                        val digits = input.substring(position, position + 4)
                        if (digits.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) invalidResponse()
                        position += 4; (digits.toIntOrNull(16) ?: invalidResponse()).toChar()
                    }
                    else -> invalidResponse()
                })
            }
            invalidResponse()
        }
        private fun number(): JsonNumber {
            val start = position
            consume('-')
            if (!consume('0')) digits()
            if (consume('.')) digits()
            if (consume('e') || consume('E')) { if (!consume('+')) consume('-'); digits() }
            return JsonNumber(input.substring(start, position))
        }
        private fun digits() {
            val start = position
            while (position < input.length && input[position] in '0'..'9') position++
            if (position == start) invalidResponse()
        }
        private fun literal(text: String, value: Any?): Any? {
            if (!input.startsWith(text, position)) invalidResponse()
            position += text.length; return value
        }
        private fun whitespace() { while (position < input.length && input[position] in " \t\r\n") position++ }
        private fun consume(ch: Char): Boolean = if (position < input.length && input[position] == ch) { position++; true } else false
        private fun expect(ch: Char) { if (!consume(ch)) invalidResponse() }
    }
}
