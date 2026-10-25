package cn.seniorvoice.demo.assist

import com.mobileagent.app.api.BModelProtocol
import com.mobileagent.app.agent.*
import java.io.File
import java.util.Base64

/** Opt-in desktop probe, no Android service, gestures, or credentials. */
object BModelLiveProbe {
    @JvmStatic fun main(args: Array<String>) {
        val stage = args[0]
        val image = Base64.getEncoder().encodeToString(File(args[1]).readBytes())
        val folder = File(args[2])
        val info = InfoPool(instruction = "我想检查 OCR 模型是否准备就绪")
        // Fixture controls are manually transcribed from the historical 1080x2340 screenshot.
        // This verifies protocol compatibility, not a current accessibility tree or phone action.
        val controls = "检查 OCR 模型是否就绪: {\"action\":\"click\",\"coordinate\":[540,257]}\n返回: {\"action\":\"click\",\"coordinate\":[540,2226]}"
        if (stage != "plan") {
            val plan = BModelProtocol.response(File(folder, "plan.json").readText())
            BModelProtocol.sections(plan, listOf("Thought", "Plan"))
            Manager().parseResponse(plan, info)
            require(info.plan.isNotBlank())
        }
        if (stage == "reflect" || stage == "verify") {
            val candidate = BModelProtocol.response(File(folder, "action.json").readText())
            val id = BModelProtocol.targetClick(candidate, setOf(0, 1))
            require(id == 0) { "unexpected_candidate_target" }
            Executor().parseResponse(candidate, info)
            info.lastAction = mapOf("action" to "click", "coordinate" to listOf(540, 257))
        }
        if (stage == "verify") {
            val reflector = ActionReflector()
            reflector.parseResponse(BModelProtocol.response(File(folder, "reflect.json").readText()), info)
            require(reflector.lastOutcome == "C") { "identical_images_should_have_no_visible_change" }
            println("APP_PARSER_PASS: plan/action/reflection; candidate=(540,257); identical images=C; phone actions=0")
            return
        }
        val prompt = when (stage) {
            "plan" -> Manager().getPrompt(info) + "\n" + controls
            "action" -> BModelProtocol.targetPrompt(info.instruction, info.plan, mapOf(0 to "检查 OCR 模型是否就绪", 1 to "返回"))
            "reflect" -> ActionReflector().getPrompt(info)
            else -> error("unknown_stage")
        }
        print(BModelProtocol.request("Qwen/Qwen3-VL-8B-Instruct", null, prompt, List(if (stage == "reflect") 2 else 1) { image }))
    }
}
