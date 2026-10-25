package cn.seniorvoice.demo.assist

import android.app.Activity
import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.*
import cn.seniorvoice.demo.BuildConfig
import com.mobileagent.app.agent.InfoPool
import com.mobileagent.app.agent.Manager
import com.mobileagent.app.api.ApiConfig
import com.mobileagent.app.api.BModelProtocol
import com.mobileagent.app.api.OpenAiCompatibleClient
import kotlinx.coroutines.*

/** Uses production transports and parsers. It never launches apps or dispatches gestures. */
class ServerRouteTestActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val vision = VisionClient()
    private val ocr = OcrWorkflow { runOnUiThread(it) }
    private val unified = VisionWorkflow { runOnUiThread(it) }
    private val medicine = MedicineVisionWorkflow { runOnUiThread(it) }
    private var job: Job? = null
    private var image: Uri? = null
    private var generation = -1L
    private lateinit var selected: TextView
    private lateinit var trace: TextView
    private lateinit var question: EditText
    private val controls = mutableListOf<Button>()
    private var dialog: AlertDialog? = null
    private val observer: (AssistUpdate) -> Unit = {
        if (job?.isActive == true && AssistRuntime.session.generation != generation)
            stopTest("当前 A/B 任务已变化，联调已停止；旧结果不会弹出。")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AssistRuntime.initialize(this)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 32)
        }
        fun text(value: String, size: Float = 18f) = TextView(this).apply {
            text = value; textSize = size; setPadding(0, 10, 0, 10); page.addView(this)
        }
        fun button(label: String, action: () -> Unit) {
            controls += Button(this).apply {
                text = label; setOnClickListener { action() }; page.addView(this)
            }
        }
        text("服务器通路测试", 26f)
        text("先在 A/B 设置中保存各服务器地址和令牌，再选择允许上传的 JPEG。此页验证真实上传、模型返回、解析和手机弹窗；每条结果分别显示。测试会结束原 A/B 任务并暂停页面观察。")
        text("OCR 建议选清晰药盒照片；B 建议选无个人信息的手机页面截图。启用交接服务器时，全部测试优先验证新 OCR、物体和组合三条通路。每次可换图；旧 A 视觉限 2 MiB，交接服务与 OCR 单张限 8 MiB，均不超过 1600 万像素。")
        button("配置服务器地址和令牌") { startActivity(Intent(this, AssistSettingsActivity::class.java)) }
        button("选择测试 JPEG") {
            runCatching {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    type = "image/jpeg"; addCategory(Intent.CATEGORY_OPENABLE)
                }, 84)
            }.onFailure { selected.text = "无法打开照片选择器" }
        }
        selected = text("尚未选择照片")
        text("图片问题（用于 OCR 和 A 视觉）")
        question = EditText(this).apply { setText("生产日期是什么？"); page.addView(this) }
        button("测试全部已配置通路") {
            val routes = mutableListOf<String>()
            if (AssistRuntime.medicineEnabled) {
                routes += listOf("交接 OCR", "交接物体", "交接组合")
            } else {
                if (AssistRuntime.unifiedVisionEnabled) routes += "统一视觉"
                if (AssistRuntime.ocrUrl.isNotBlank()) routes += "OCR"
                routes += listOf("A 视觉", "B 页面分析")
            }
            if (AssistRuntime.bAgentUrl.isNotBlank()) routes += "B Agent"
            startTest(routes)
        }
        button("单独测试交接 OCR（原始 JPEG → 异步任务）") { startTest(listOf("交接 OCR")) }
        button("单独测试交接物体识别（真实图片 → Qwen）") { startTest(listOf("交接物体")) }
        button("单独测试交接组合（同图 OCR + 物体识别）") { startTest(listOf("交接组合")) }
        for (route in listOf("OCR", "A 视觉", "B 页面分析", "B Agent"))
            button("单独测试 ${if (route in setOf("OCR", "A 视觉", "B 页面分析")) "旧版 $route" else route}") { startTest(listOf(route)) }
        button("单独测试统一视觉任务（OCR / 物体 / 组合）") { startTest(listOf("统一视觉")) }
        page.addView(Button(this).apply { text = "取消本次测试"; setOnClickListener { stopTest("用户取消测试。") } })
        text("打开应用、拨号和 Vosk 语音识别在本机运行，不需要服务器。B 的本页测试只验证图片输入和计划解析；真实停留检测、B 录音、确认后的一次点击仍需另行实机验证。此页不执行模型建议。")
        trace = text("等待测试。")
        trace.setTextIsSelectable(true)
        page.addView(Button(this).apply { text = "返回"; setOnClickListener { finish() } })
        setContentView(ScrollView(this).apply { addView(page) })
    }

    private fun startTest(routes: List<String>) {
        val uri = image ?: return toast("请先选择 JPEG")
        val prompt = question.text.toString().trim()
        if (prompt.length !in 1..1000) return toast("请输入 1～1000 字的问题")
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) return toast("请先解锁手机")
        AssistRuntime.cancelAll("开始服务器通路测试")
        AssistRuntime.setConsent(false)
        generation = AssistRuntime.session.generation
        dialog?.dismiss()
        trace.text = "开始真实请求，等待服务器返回…\n"
        controls.forEach { it.isEnabled = false }
        question.isEnabled = false
        // Snapshot settings on Main so a request never mixes two configurations.
        val ocrBase = AssistRuntime.ocrUrl
        val ocrToken = AssistRuntime.ocrToken
        val ocrLan = AssistRuntime.ocrLan
        val visualBase = AssistRuntime.baseUrl
        val visualToken = AssistRuntime.apiToken
        val agentBase = AssistRuntime.bAgentUrl
        val agentToken = AssistRuntime.bAgentToken
        val agentModel = AssistRuntime.bAgentModel
        val agentLan = AssistRuntime.bAgentLan
        val unifiedBase = AssistRuntime.unifiedVisionUrl
        val unifiedToken = AssistRuntime.unifiedVisionToken
        val unifiedLan = AssistRuntime.unifiedVisionLan
        val medicineBase = AssistRuntime.medicineUrl
        val medicineToken = AssistRuntime.medicineToken
        val medicineLan = AssistRuntime.medicineLan
        job = scope.launch {
            try {
                val jpeg = withContext(Dispatchers.IO) { readJpeg(uri) }
                append("图片已读取：${jpeg.size} 字节；将发送至设置中对应服务。")
                val results = mutableListOf<String>()
                for (route in routes) {
                    ensureActive()
                    append("\n$route：正在请求…")
                    val outcome = try {
                        val reply = when (route) {
                            "交接 OCR", "交接物体", "交接组合" -> {
                                require(medicineBase.isNotBlank() && medicineToken.isNotBlank()) { "请先保存交接服务器根地址和 VISION_API_TOKEN" }
                                val config = ServerConfig.parse(medicineBase, medicineLan, BuildConfig.DEBUG)
                                val id = OcrProtocol.newId()
                                val mode = when (route) {
                                    "交接 OCR" -> VisionMode.OCR
                                    "交接物体" -> VisionMode.OBJECT
                                    else -> VisionMode.COMBINED
                                }
                                append("交接服务：${config.base}，$mode，任务编号：$id")
                                append(if (mode == VisionMode.OBJECT) "接口：POST /api/v1/object-recognition"
                                    else "接口：POST /api/v1/vision/tasks → PUT 原始 JPEG → GET 本次任务" +
                                        if (mode == VisionMode.COMBINED) " → POST /api/v1/object-recognition" else "")
                                val answer = suspendCancellableCoroutine<MedicineVisionApiClient.Answer> { continuation ->
                                    continuation.invokeOnCancellation { medicine.cancel() }
                                    medicine.start(config, medicineToken, id, prompt, mode, jpeg, recordId = "route-$id",
                                        created = { error("本页已选真实 JPEG，不应要求硬件拍照") },
                                        progress = { if (continuation.isActive) append("$route：$it") },
                                        finished = { if (continuation.isActive) continuation.resumeWith(it) })
                                }
                                answer.display()
                            }
                            "统一视觉" -> {
                                val config = ServerConfig.parse(unifiedBase, unifiedLan, BuildConfig.DEBUG)
                                val id = OcrProtocol.newId(); val mode = VisionApiClient.mode(prompt)
                                append("统一视觉：$mode，任务编号：$id")
                                val answer = suspendCancellableCoroutine<VisionApiClient.Answer> { continuation ->
                                    continuation.invokeOnCancellation { unified.cancel() }
                                    unified.start(config, unifiedToken, id, prompt, mode, jpeg,
                                        created = {}, progress = { if (continuation.isActive) append("统一视觉：$it") },
                                        finished = { if (continuation.isActive) continuation.resumeWith(it) })
                                }
                                answer.display()
                            }
                            "OCR" -> {
                                require(ocrBase.isNotBlank() && ocrToken.isNotBlank()) { "未配置 OCR 地址或 Bearer 令牌" }
                                require(OcrProtocol.supports(prompt)) { "OCR 问题需为读文字、药名、生产日期、有效期或批号" }
                                val base = OcrProtocol.endpoint(ocrBase, ocrLan, BuildConfig.DEBUG)
                                val id = OcrProtocol.newId()
                                append("OCR 任务编号：$id")
                                val answer = suspendCancellableCoroutine<OcrProtocol.Answer> { continuation ->
                                    continuation.invokeOnCancellation { ocr.cancel() }
                                    ocr.start(base, ocrToken, id, OcrProtocol.normalizeQuestion(prompt), jpeg,
                                        OcrApiClient.sha256(jpeg), false, created = {},
                                        progress = { if (continuation.isActive) append("OCR：$it") },
                                        finished = { if (continuation.isActive) continuation.resumeWith(it) })
                                }
                                "回答类型：${answer.type}\n${answer.display()}"
                            }
                            "A 视觉" -> withContext(Dispatchers.IO) {
                                VisionClient.answer(vision.call(visualBase, visualToken, jpeg, prompt))
                            }
                            "B 页面分析" -> withContext(Dispatchers.IO) {
                                val response = vision.call(visualBase, visualToken, jpeg)
                                val scene = VisionClient.scene(response)
                                "$scene\n帮助建议：${response.optJSONObject("analysis")?.optJSONArray("suggested_assistance") ?: "无"}"
                            }
                            else -> {
                                require(agentBase.isNotBlank() && agentToken.isNotBlank()) { "未配置 B 自建代理地址或 B_PROXY_TOKEN" }
                                val endpoint = BAgentConfig.validate(agentBase, agentModel, agentToken, agentLan, BuildConfig.DEBUG)
                                val info = InfoPool(instruction = "请用中文说明截图中的内容并提供操作建议；本次仅做通路验证，不执行操作。")
                                withContext(Dispatchers.IO) {
                                    val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: error("图片解码失败")
                                    try {
                                        val manager = Manager()
                                        val response = OpenAiCompatibleClient(ApiConfig(endpoint = endpoint, apiKey = agentToken, model = agentModel))
                                            .predictWithImages(null, manager.getPrompt(info), listOf(bitmap)).getOrThrow()
                                        BModelProtocol.sections(response, listOf("Thought", "Plan"))
                                        manager.parseResponse(response, info)
                                        check(info.plan.isNotBlank()) { "B Agent 未解析到计划" }
                                        "真实图片已提交；Thought / Plan 解析通过。\n${info.plan}\n本次没有操作手机。"
                                    } finally { bitmap.recycle() }
                                }
                            }
                        }
                        "$route：请求与解析通过\n$reply"
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { "$route：未通过\n${error.message ?: "请求或解析失败"}" }
                    ensureActive()
                    results += outcome
                    append(outcome)
                }
                val message = results.joinToString("\n\n")
                if (!isFinishing && !isDestroyed && !getSystemService(KeyguardManager::class.java).isKeyguardLocked &&
                    AssistRuntime.session.generation == generation) {
                    val body = TextView(this@ServerRouteTestActivity).apply {
                        text = message; textSize = 19f; setPadding(24, 16, 24, 16); setTextIsSelectable(true)
                    }
                    dialog = AlertDialog.Builder(this@ServerRouteTestActivity).setTitle("服务器通路测试结果")
                        .setView(ScrollView(this@ServerRouteTestActivity).apply { addView(body) })
                        .setPositiveButton("知道了", null).show()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { append("测试未能开始：${error.message ?: "照片读取失败"}") }
            finally { controls.forEach { it.isEnabled = true }; question.isEnabled = true }
        }
    }

    private fun readJpeg(uri: Uri): ByteArray {
        require(uri.scheme == "content") { "请通过系统选择器选图" }
        val bytes = contentResolver.openInputStream(uri)?.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= 8 * 1024 * 1024) { "照片超过 8 MiB" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: error("照片无法读取，请重新选择")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outMimeType == "image/jpeg" && bounds.outWidth > 0 && bounds.outHeight > 0 &&
            bounds.outWidth.toLong() * bounds.outHeight <= 16_000_000) { "需要真实 JPEG，最多 1600 万像素" }
        return bytes
    }

    private fun append(message: String) { trace.append("$message\n") }
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    private fun stopTest(reason: String) {
        if (job?.isActive == true) { job?.cancel(); vision.cancelAll(); ocr.cancel(); unified.cancel(); medicine.cancel(); append(reason) }
        else medicine.cancel()
        dialog?.dismiss(); dialog = null
    }
    @Deprecated("Uses platform document picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 84 && resultCode == RESULT_OK) data?.data?.let {
            image = it; selected.text = "已选 JPEG；点击下方测试按钮才会上传。"
        }
    }
    override fun onResume() { super.onResume(); AssistRuntime.addListener(observer) }
    override fun onPause() {
        stopTest("页面离开，测试已取消；重新进入后可重试。")
        AssistRuntime.removeListener(observer); super.onPause()
    }
    override fun onDestroy() { scope.cancel(); vision.cancelAll(); ocr.close(); unified.close(); medicine.close(); super.onDestroy() }
}
