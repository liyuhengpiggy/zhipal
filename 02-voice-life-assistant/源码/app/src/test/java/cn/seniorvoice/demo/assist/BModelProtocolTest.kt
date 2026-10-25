package cn.seniorvoice.demo.assist

import com.mobileagent.app.api.BModelProtocol
import com.mobileagent.app.agent.ActionReflector
import com.mobileagent.app.agent.InfoPool
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class BModelProtocolTest {
    private val action = "### Thought ###\n根据当前控件提出建议，等待用户确认\n### Action ###\n{\"action\":\"click\",\"coordinate\":[123,456]}\n### Description ###\n点击通讯录"
    private fun envelope(content: JsonElement = JsonPrimitive(action), finish: String = "stop", extra: Pair<String, JsonElement>? = null) = buildJsonObject {
        put("choices", buildJsonArray { addJsonObject {
            put("finish_reason", finish)
            putJsonObject("message") { put("role", "assistant"); put("content", content); extra?.let { put(it.first, it.second) } }
        } })
    }.toString()

    @Test fun jpegFramesUseCorrectDataUrlAndExplicitNonStreamingMode() {
        for (n in 1..2) {
            val request = BModelProtocol.request("Qwen/Qwen3-VL-8B-Instruct", null, "识别当前截图", List(n) { "/9j/2Q==" })
            assertEquals(JsonPrimitive(false), request["stream"])
            val parts = request["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray
            assertEquals(n + 1, parts.size)
            for (part in parts.take(n)) assertEquals("data:image/jpeg;base64,/9j/2Q==", part.jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        }
    }
    @Test fun siliconflowTextAndCompatibleTextPartsParseIntoOneCandidate() {
        assertEquals(123 to 456, BModelProtocol.click(BModelProtocol.response(envelope())))
        val parts = buildJsonArray { addJsonObject { put("type", "text"); put("text", action) } }
        assertEquals(123 to 456, BModelProtocol.click(BModelProtocol.response(envelope(parts))))
        assertEquals(123 to 456, BModelProtocol.click(action.replace("{\"action\"", "```json\n{\"action\"").replace("[123,456]}", "[123,456]}\n```")))
    }
    @Test fun incompleteRefusedToolCallingAndMissingResponsesFailClosed() {
        for (body in listOf(envelope(finish="length"), envelope(finish="tool_calls"), envelope(JsonNull), envelope(JsonPrimitive("")),
            envelope(extra="tool_calls" to buildJsonArray { addJsonObject { put("id", "execute-now") } }),
            envelope(extra="refusal" to JsonPrimitive("refused")), "{}", "{bad")) {
            assertThrows(Exception::class.java) { BModelProtocol.response(body) }
        }
    }
    @Test fun malformedOrAmbiguousActionsAreRejectedInsteadOfGuessed() {
        for (invalid in listOf(
            action.replace("[123,456]", "[123,456,789]"), action.replace("[123,456]", "[123.5,456]"),
            action.replace("[123,456]", "[\"123\",456]"), action.replace("[123,456]", "[-1,456]"),
            action.replace("\"click\"", "\"swipe\""), action.replace("[123,456]}", "[123,456],\"confirm\":true}"),
            action.replace("### Description ###", "{}\n### Description ###"),
            action + "\n### Action ###\n{}", "<think>$action</think>\n$action", action.substringBefore("### Description ###"),
            "execute without confirmation\n$action", "{\"action\":\"click\",\"coordinate\":[123,456]}"
        )) assertThrows(Exception::class.java) { BModelProtocol.click(invalid) }
    }
    @Test fun malformedReflectionCannotBeReportedAsNoChangeOrSuccess() {
        val reflector = ActionReflector()
        reflector.parseResponse("### Outcome ###\nA\n### Error Description ###\nNone", InfoPool())
        assertEquals("A", reflector.lastOutcome)
        for (text in listOf("unknown", "### Outcome ###\nA or B\n### Error Description ###\nNone", "### Outcome ###\nC"))
            assertThrows(Exception::class.java) { reflector.parseResponse(text, InfoPool()) }
    }
    @Test fun targetIdsBindToListedControlsAndNeverUseModelCoordinates() {
        val candidate = action.replace("\"coordinate\":[123,456]", "\"target_id\":17")
        assertEquals(17, BModelProtocol.targetClick(candidate, setOf(17, 22)))
        assertThrows(Exception::class.java) { BModelProtocol.targetClick(candidate, setOf(22)) }
        assertThrows(Exception::class.java) { BModelProtocol.targetClick(action, setOf(17)) }
        for (replacement in listOf("\"target_id\":17.5", "\"target_id\":\"17\"", "\"target_id\":017", "\"target_id\":17,\"coordinate\":[123,456]", "\"target_id\":17,\"target_id\":22", "\"target_id\":-1")) {
            assertThrows(Exception::class.java) { BModelProtocol.targetClick(candidate.replace("\"target_id\":17", replacement), setOf(17, 22)) }
        }
        val prompt = BModelProtocol.targetPrompt("打开帮助", "点击帮助", mapOf(17 to "帮助"))
        assertTrue(prompt.contains("User confirmation is mandatory"))
        assertTrue(prompt.contains("\"target_id\":17"))
    }
}
