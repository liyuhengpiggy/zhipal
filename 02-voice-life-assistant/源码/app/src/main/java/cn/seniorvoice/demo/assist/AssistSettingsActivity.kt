package cn.seniorvoice.demo.assist

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.text.Editable
import android.text.TextWatcher
import android.widget.*
import cn.seniorvoice.demo.BuildConfig
import cn.seniorvoice.demo.appcontrol.InstalledAppDirectory
import cn.seniorvoice.demo.setup.PermissionRecovery
import java.net.URI
import java.util.UUID

class AssistSettingsActivity : Activity() {
    private lateinit var enabled: CheckBox
    private lateinit var url: EditText
    private lateinit var token: EditText
    private lateinit var dwell: EditText
    private lateinit var status: TextView
    private lateinit var ocrUrl: EditText
    private lateinit var ocrToken: EditText
    private lateinit var ocrLan: CheckBox
    private lateinit var ocrQuestion: EditText
    private lateinit var ocrHealth: TextView
    private lateinit var agentEnabled: CheckBox
    private lateinit var agentUrl: EditText
    private lateinit var agentModel: EditText
    private lateinit var agentToken: EditText
    private lateinit var agentLan: CheckBox
    private lateinit var aCommandEnabled: CheckBox
    private lateinit var aCommandUrl: EditText
    private lateinit var aCommandToken: EditText
    private lateinit var aCommandLan: CheckBox
    private lateinit var aCommandScreen: CheckBox
    private lateinit var aCommandQuestion: EditText
    private lateinit var aCommandResult: TextView
    private val aCommandTestWorker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var aCommandTestClient: ACommandApiClient? = null
    private var aCommandTestGeneration = 0L
    private var aCommandTestRunning = false
    private var resumed = false
    private lateinit var unifiedEnabled: CheckBox
    private lateinit var unifiedUrl: EditText
    private lateinit var unifiedToken: EditText
    private lateinit var unifiedLan: CheckBox
    private lateinit var medicineEnabled: CheckBox
    private lateinit var medicineUrl: EditText
    private lateinit var medicineToken: EditText
    private lateinit var medicineLan: CheckBox
    private lateinit var medicineUseB: CheckBox
    private lateinit var medicineHealth: TextView
    private var medicineHealthClient: MedicineVisionApiClient? = null
    private val healthClient = OcrApiClient()
    private val healthWorker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var pickerGeneration = -1L
    private var pickerQuestion = ""
    private val observer: (AssistUpdate) -> Unit = { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AssistRuntime.initialize(this)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 40)
        }
        fun text(value: String, size: Float = 17f) {
            page.addView(TextView(this).apply { text = value; textSize = size; setPadding(0, 12, 0, 8) })
        }
        fun button(value: String, action: () -> Unit) {
            page.addView(Button(this).apply { text = value; setOnClickListener { action() } })
        }
        fun input(label: String, value: String, type: Int): EditText {
            text(label)
            return EditText(this).apply { setText(value); inputType = type; setSingleLine(); page.addView(this) }
        }
        text("A/B 眼镜帮助", 26f)
        page.addView(CheckBox(this).apply {
            text = "朗读 A 结果和 B 确认问题（手机中文 TTS）"; isChecked = AssistRuntime.ttsEnabled
            setOnCheckedChangeListener { _, checked -> AssistRuntime.configureTts(checked) }
        })
        button("测试手机中文朗读") { AssistRuntime.testTts() }
        button("一键检查 / 修复必要权限") {
            PermissionRecovery.open(this, "A/B 需要蓝牙、电话和后台连接等权限；页面操作还需要无障碍服务。请逐项检查并确认系统授权。", autoStart = true)
        }
        button("查看 / 导出脱敏联调日志") { startActivity(Intent(this, IntegrationDiagnosticsActivity::class.java)) }
        button("服务器通路测试（选图 → 服务器 → 手机结果）") {
            startActivity(Intent(this, ServerRouteTestActivity::class.java))
        }
        text("A：支持硬件 v1/en 文字、原有 WAV 和带 A/B 元信息的 PCM 分块。PCM 需固件按补充协议发送。有效文字或 A 开始事件到达后，清理 B 旧任务；仅有文字帧时，手机无法提前获知硬件开始录音。")
        text("B：页面长时间未变化后询问“需要我的帮助吗？”。按眼镜 B 录音回答；只说“需要”时继续询问目标。开启下方 B Agent 后，提出一步点击并询问确认；再次按 B 说“对”才执行一次，再检查结果并停止。")
        enabled = CheckBox(this).apply { text = "启用 B 页面帮助"; isChecked = AssistRuntime.enabled; page.addView(this) }
        url = input("视觉服务地址", AssistRuntime.baseUrl, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        token = input("接口令牌（后端未要求时留空）", AssistRuntime.apiToken,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        dwell = input("同一页面停留秒数（10～120）", AssistRuntime.dwellSeconds.toString(), InputType.TYPE_CLASS_NUMBER)
        text("B Agent 视觉模型", 23f)
        text("连接自建云端代理（以 /v1 结尾，也可填完整 /v1/chat/completions）。硅基流动密钥仅在云端配置，手机填写独立代理令牌。这与 OCR 地址、原视觉服务地址分别配置。关闭 Agent 时仍使用原来的文字建议流程。")
        agentEnabled = CheckBox(this).apply { text = "启用 B Agent 单步操作"; isChecked = AssistRuntime.bAgentEnabled; page.addView(this) }
        agentUrl = input("B 云端代理地址（填自建代理的 /v1 地址）", AssistRuntime.bAgentUrl, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        agentUrl.hint = if (BuildConfig.DEBUG) "https://模型服务/v1 或内网 http://IP:端口/v1" else "https://模型服务/v1"
        agentModel = input("B 视觉模型名称（与服务提供的模型 ID 一致）", AssistRuntime.bAgentModel, InputType.TYPE_CLASS_TEXT)
        agentToken = input("B 代理访问令牌（B_PROXY_TOKEN，勿填硅基流动密钥）", AssistRuntime.bAgentToken, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        agentLan = CheckBox(this).apply {
            text = "B 模型允许局域网 HTTP（仅调试版）"; isChecked = AssistRuntime.bAgentLan
            isEnabled = BuildConfig.DEBUG; page.addView(this)
        }
        button("保存 A/B 与 Agent 配置") { save() }
        text("页面截图先在手机比较；持续停留后才将当前截图发给上面的服务。密码、支付、输入中和锁屏等页面会跳过。结束本次观察或重启应用后，需要重新开始。")
        button("1. 打开系统无障碍设置") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        button("2. 同意截图分析，开始本次页面观察") {
            if (!save()) return@button
            if (!AssistRuntime.enabled) { notice("请先勾选启用 B 页面帮助"); return@button }
            if (!AssistRuntime.accessibilityConnected) { notice("请先开启“语音生活助手 A/B 页面帮助”无障碍服务，再返回这里"); return@button }
            AssistRuntime.setConsent(true)
            notificationPermission()
            AssistRuntime.ensureService()
            refresh()
        }
        button("结束本次页面观察") { AssistRuntime.setConsent(false); refresh() }
        button("保持蓝牙后台连接") { notificationPermission(); AssistRuntime.ensureService() }
        text("A 智能指令", 23f)
        text("打开应用、拨号等明确指令优先在手机处理。其余 A 指令可交由自建代理解析；代理需支持 POST /v1/commands/interpret。第三方模型 API Key 只配置在服务器。A 任务始终保留在 A 中处理。")
        aCommandEnabled = CheckBox(this).apply {
            text = "启用 A 智能指令解析"; isChecked = AssistRuntime.aCommandEnabled; page.addView(this)
        }
        aCommandUrl = input("A 指令代理根地址", AssistRuntime.aCommandUrl,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        aCommandUrl.hint = if (BuildConfig.DEBUG) "https://自建代理 或内网 http://IP:端口" else "https://自建代理"
        aCommandToken = input("A 代理访问令牌（勿填第三方 sk- 密钥）", AssistRuntime.aCommandToken,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD).apply { isSaveEnabled = false }
        aCommandLan = CheckBox(this).apply {
            text = "A 指令允许局域网 HTTP（仅调试版）"
            isChecked = AssistRuntime.aCommandLan && BuildConfig.DEBUG; isEnabled = BuildConfig.DEBUG; page.addView(this)
        }
        aCommandScreen = CheckBox(this).apply {
            text = "同意仅在 A 任务需要时发送当前真实控件树给代理"
            isChecked = AssistRuntime.aCommandScreenEnabled; page.addView(this)
        }
        text("控件树可能含当前页面文字；仅在勾选授权且 A 需要页面操作时发送。执行前会重新检查当前页面和目标。")
        button("沿用上方 B 代理地址与令牌") { copyBAgentForACommand() }
        button("保存 A 智能指令配置") { saveACommand() }
        aCommandQuestion = input("A 模型解析测试内容", "帮我打开微信", InputType.TYPE_CLASS_TEXT)
        text("本测试发送输入内容与真实已安装应用清单，联系人和页面控件树不参与。仅显示解析结果，不启动应用、拨号或点击；无需先启用 A。")
        aCommandResult = TextView(this).apply { text = "尚未测试 A 指令代理"; textSize = 16f; page.addView(this) }
        button("测试 A 模型解析（不执行）") { testACommand() }
        button("取消 A 解析测试") { cancelACommandTest() }
        val aCommandEdits = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                cancelACommandTest()
                aCommandResult.text = "输入已修改，请重新测试 A 模型解析（不执行手机操作）"
            }
            override fun afterTextChanged(s: Editable?) = Unit
        }
        listOf(aCommandUrl, aCommandToken, aCommandQuestion).forEach { it.addTextChangedListener(aCommandEdits) }
        listOf(aCommandEnabled, aCommandLan, aCommandScreen).forEach { checkbox ->
            checkbox.setOnCheckedChangeListener { _, _ -> cancelACommandTest() }
        }
        text("A 文字与药盒识别", 23f)
        text("交接视觉服务器（APP_SERVER_HANDOFF）", 21f)
        text("使用队友新交付的服务：文字走异步 OCR 任务，物体走独立识别接口；启用后 A 优先使用此处配置。这里只填服务器根地址及服务器访问令牌；模型 API Key 配在服务器。手机不能填 127.0.0.1。")
        medicineEnabled = CheckBox(this).apply {
            text = "优先使用交接视觉服务器"; isChecked = AssistRuntime.medicineEnabled; page.addView(this)
        }
        medicineUrl = input("交接服务根地址", AssistRuntime.medicineUrl, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        medicineUrl.hint = if (BuildConfig.DEBUG) "http://电脑局域网IP:8000 或 https://云端根地址" else "https://视觉服务器根地址"
        medicineToken = input("交接服务访问令牌（VISION_API_TOKEN）", AssistRuntime.medicineToken,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        medicineLan = CheckBox(this).apply {
            text = "交接服务允许局域网 HTTP（仅调试版）"; isChecked = AssistRuntime.medicineLan
            isEnabled = BuildConfig.DEBUG; page.addView(this)
        }
        medicineUseB = CheckBox(this).apply {
            text = "同时将此服务器用于 B Agent 代理（/v1）"
            isChecked = AssistRuntime.bAgentEnabled && AssistRuntime.medicineUrl.isNotBlank() &&
                AssistRuntime.bAgentUrl.trimEnd('/') == AssistRuntime.medicineUrl.trimEnd('/') + "/v1" &&
                AssistRuntime.bAgentToken == AssistRuntime.medicineToken
            page.addView(this)
        }
        text("勾选 B 选项并保存后，会更新上方 B 代理地址、令牌和模型；B 页面观察仍需单独授权。取消勾选不会自动关闭现有 B 配置。")
        button("保存交接服务器配置") { if (saveMedicine()) notice("交接服务配置已保存") }
        medicineHealth = TextView(this).apply { text = "尚未检查交接服务器"; textSize = 16f; page.addView(this) }
        button("检查交接服务与模型能力（/health）") {
            if (!saveMedicine()) return@button
            medicineHealthClient?.cancel()
            val prepared = runCatching {
                MedicineVisionApiClient(ServerConfig.parse(AssistRuntime.medicineUrl, AssistRuntime.medicineLan, BuildConfig.DEBUG), AssistRuntime.medicineToken)
            }
            val client = prepared.getOrElse { medicineHealth.text = it.message ?: "交接服务配置无效"; return@button }
            medicineHealthClient = client
            val ticket = client.ticket()
            medicineHealth.text = "正在请求交接服务 /health…"
            healthWorker.execute {
                val outcome = runCatching { client.health(ticket) }
                runOnUiThread {
                    if (!isDestroyed && !isFinishing && medicineHealthClient === client) {
                        medicineHealth.text = outcome.fold({ health ->
                            val ready = health.ready && health.status != "degraded"
                            "服务状态：${health.status}；${if (ready) "已就绪" else "初始化中或不可用，请稍后重试"}\n" +
                                "OCR：${if ("ocr" in health.capabilities) "已配置" else "不可用"}\n" +
                                "物体识别：${if ("object_recognition" in health.capabilities) "已配置" else "不可用"}\n" +
                                "B 代理：${if ("mobileagentb_proxy" in health.capabilities) "已配置" else "不可用"}\n" +
                                "能力已配置不等于推理成功，请再选真实 JPEG 验证。"
                        }, { it.message ?: "交接服务检查失败" })
                    }
                }
            }
        }
        text("统一视觉任务接口（需后端和固件同时支持）", 21f)
        text("后端提供 OCR / OBJECT / COMBINED 任务、限时上传凭证后再开启。开启后 A 的文字和物体问题都走同一任务接口；关闭时保留现有 OCR 和实物识别配置。")
        unifiedEnabled = CheckBox(this).apply { text = "使用统一视觉任务接口"; isChecked = AssistRuntime.unifiedVisionEnabled; page.addView(this) }
        unifiedUrl = input("统一任务服务根地址", AssistRuntime.unifiedVisionUrl, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        unifiedToken = input("统一任务服务令牌（非第三方模型密钥）", AssistRuntime.unifiedVisionToken, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        unifiedLan = CheckBox(this).apply { text = "统一任务允许内网 HTTP（仅调试版）"; isChecked = AssistRuntime.unifiedVisionLan; isEnabled = BuildConfig.DEBUG; page.addView(this) }
        button("保存统一视觉配置") {
            runCatching { AssistRuntime.configureUnifiedVision(unifiedEnabled.isChecked, unifiedUrl.text.toString(), unifiedToken.text.toString(), unifiedLan.isChecked) }
                .onSuccess { notice("统一视觉配置已保存") }.onFailure { notice(it.message ?: "配置错误") }
        }
        text("填写队友部署的 OCR 服务根地址和令牌。可读取可见文字、生产日期、有效期、批号等；药盒字段会保留候选提示和识别原文。")
        ocrUrl = input("OCR 服务根地址", AssistRuntime.ocrUrl, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        ocrUrl.hint = if (BuildConfig.DEBUG) "http://192.168.1.17:8001" else "https://medicine-vision-ocr.onrender.com"
        ocrToken = input("OCR Bearer 令牌", AssistRuntime.ocrToken, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        ocrLan = CheckBox(this).apply {
            text = if (BuildConfig.DEBUG) "允许局域网 HTTP 联调（明文，仅内网 IPv4）" else "正式版仅允许 HTTPS"
            isChecked = AssistRuntime.ocrLan; isEnabled = BuildConfig.DEBUG; page.addView(this)
        }
        button("保存 OCR 配置") { if (saveOcr()) notice("OCR 配置已保存") }
        ocrHealth = TextView(this).apply { text = "尚未检查 OCR 模型"; textSize = 16f; page.addView(this) }
        button("检查 OCR 模型是否就绪") {
            if (!saveOcr()) return@button
            healthClient.cancelAll()
            val ticket = healthClient.ticket()
            val base = OcrProtocol.endpoint(AssistRuntime.ocrUrl, AssistRuntime.ocrLan, BuildConfig.DEBUG)
            val secret = AssistRuntime.ocrToken
            ocrHealth.text = "正在检查 OCR…"
            healthWorker.execute {
                val outcome = runCatching { healthClient.ready(base, secret, ticket) }
                runOnUiThread {
                    if (!isDestroyed && !isFinishing && healthClient.ticket() == ticket)
                        ocrHealth.text = outcome.fold({ "OCR 模型已就绪；还需用照片验证识别效果" }, { it.message ?: "检查失败" })
                }
            }
        }
        ocrQuestion = input("照片问题", "生产日期是什么？", InputType.TYPE_CLASS_TEXT)
        button("选择 JPEG 测试识别（作为新 A 任务）") {
            if ((medicineEnabled.isChecked || AssistRuntime.medicineEnabled) && !saveMedicine()) return@button
            if (!AssistRuntime.medicineEnabled && !AssistRuntime.unifiedVisionEnabled && !saveOcr()) return@button
            val question = ocrQuestion.text.toString().trim()
            if (question.length !in 1..1000 || (!AssistRuntime.medicineEnabled && !AssistRuntime.unifiedVisionEnabled && !OcrProtocol.supports(question))) {
                notice("请输入读文字、药名、生产日期、有效期或批号等问题"); return@button
            }
            pickerGeneration = AssistRuntime.session.generation; pickerQuestion = question
            runCatching {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    type = "image/jpeg"; addCategory(Intent.CATEGORY_OPENABLE)
                }, 73)
            }.onFailure { notice("无法打开文件选择器") }
        }
        button("按原编号查询上次 OCR 任务") {
            if ((medicineEnabled.isChecked || AssistRuntime.medicineEnabled) && !saveMedicine()) return@button
            if (AssistRuntime.medicineEnabled || saveOcr()) AssistRuntime.resumeLastOcr()
        }
        text("测试照片最多 8 MiB、1600 万像素。眼镜拍照需要固件支持本次任务的 JPEG 上传。识别不清请重拍；多个候选不自动选择。")
        button("取消当前 A/B 任务") { AssistRuntime.cancelAll("已取消当前任务"); refresh() }
        button("停止后台连接与全部任务") {
            AssistRuntime.stop()
            stopService(Intent(this, VoiceForegroundService::class.java))
            refresh()
        }
        text("华为手机：建议在系统“应用启动管理”中允许本助手后台活动。首次使用先在首页完成蓝牙和电话权限，再搜索并连接眼镜。")
        status = TextView(this).apply { textSize = 16f; setPadding(0, 20, 0, 16); page.addView(this) }
        button("返回") { finish() }
        setContentView(ScrollView(this).apply { addView(page) })
    }

    private fun save(): Boolean {
        val value = url.text.toString().trim().trimEnd('/')
        val uri = runCatching { URI(value) }.getOrNull()
        if (uri == null || uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null || uri.query != null || uri.fragment != null) {
            notice("请填写 HTTPS 服务地址，例如默认 Render 地址，不包含 /docs"); return false
        }
        if (value.endsWith("/docs") || value.endsWith("/openapi.json") || value.endsWith("/upload")) { notice("这里只填服务根地址，请去掉 /docs、/openapi.json 或 /upload"); return false }
        val seconds = dwell.text.toString().toIntOrNull()
        if (seconds == null || seconds !in 10..120) { notice("停留秒数需为 10～120"); return false }
        val secret = token.text.toString().trim()
        if (secret.contains('\r') || secret.contains('\n')) { notice("令牌不能包含换行"); return false }
        val agentError = runCatching {
            if (agentEnabled.isChecked) BAgentConfig.validate(agentUrl.text.toString(), agentModel.text.toString(),
                agentToken.text.toString(), agentLan.isChecked, BuildConfig.DEBUG)
        }.exceptionOrNull()
        if (agentError != null) { notice(agentError.message ?: "B 模型配置错误"); return false }
        AssistRuntime.configure(enabled.isChecked, value, secret, seconds)
        AssistRuntime.configureBAgent(agentEnabled.isChecked, agentUrl.text.toString(), agentModel.text.toString(),
            agentToken.text.toString(), agentLan.isChecked)
        refresh(); return true
    }

    private fun notificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 71)
    }
    private fun copyBAgentForACommand() {
        cancelACommandTest()
        val secret = agentToken.text.toString().trim()
        val endpoint = runCatching {
            ACommandProtocol.validateToken(secret)
            ACommandProtocol.endpoint(agentUrl.text.toString(), agentLan.isChecked, BuildConfig.DEBUG)
        }.getOrElse { notice(it.message ?: "B 代理配置无效，无法沿用"); return }
        aCommandUrl.setText(URI(endpoint.scheme, null, endpoint.host, endpoint.port, null, null, null).toString())
        aCommandToken.setText(secret)
        aCommandLan.isChecked = agentLan.isChecked && BuildConfig.DEBUG
        notice("已复制 B 代理根地址与令牌，请保存 A 配置；启用状态和控件树授权由你单独选择")
    }
    private fun saveACommand() {
        cancelACommandTest()
        val value = aCommandUrl.text.toString().trim()
        val secret = aCommandToken.text.toString().trim()
        val outcome = runCatching {
            if (aCommandEnabled.isChecked)
                ACommandProtocol.endpoint(value, aCommandLan.isChecked, BuildConfig.DEBUG)
            if (aCommandEnabled.isChecked || secret.isNotBlank()) ACommandProtocol.validateToken(secret)
            AssistRuntime.configureACommand(aCommandEnabled.isChecked, value, secret,
                aCommandLan.isChecked, aCommandScreen.isChecked)
        }
        if (outcome.isFailure) { notice(outcome.exceptionOrNull()?.message ?: "A 指令配置无效"); return }
        aCommandResult.text = "A 指令配置已保存，可先进行不执行的解析测试"
        notice("A 智能指令配置已保存")
        refresh()
    }
    private fun testACommand() {
        cancelACommandTest()
        val utterance = aCommandQuestion.text.toString().trim()
        val taskId = UUID.randomUUID().toString()
        val client = runCatching {
            ACommandProtocol.validateRequest(ACommandRequest(taskId, utterance, emptyList(), emptyList()))
            ACommandApiClient(aCommandUrl.text.toString(), aCommandToken.text.toString().trim(),
                aCommandLan.isChecked, BuildConfig.DEBUG)
        }.getOrElse { aCommandResult.text = it.message ?: "请检查 A 代理配置和测试内容"; return }
        aCommandTestClient = client
        val generation = aCommandTestGeneration
        val ticket = client.ticket()
        aCommandTestRunning = true
        aCommandResult.text = "正在读取真实应用清单并解析；网络请求上限 20 秒…\ntask_id：$taskId"
        aCommandTestWorker.execute {
            var apps = emptyList<ACommandApp>()
            val outcome = runCatching {
                // Drop labels rejected by the wire contract; never invent an application name or ID.
                apps = InstalledAppDirectory(applicationContext).listLaunchableApps()
                    .distinctBy { it.packageName }.mapNotNull { app ->
                        val candidate = ACommandApp(app.packageName, app.label)
                        val valid = runCatching {
                            ACommandProtocol.validateRequest(ACommandRequest(taskId, utterance, listOf(candidate), emptyList()))
                        }.isSuccess
                        if (valid) candidate else null
                    }.take(ACommandProtocol.MAX_APPS)
                val request = ACommandProtocol.fitForProxy(ACommandRequest(taskId, utterance, apps, emptyList()))
                apps = request.apps
                client.interpret(request, ticket)
            }
            client.close()
            runOnUiThread {
                if (!resumed || isDestroyed || isFinishing || generation != aCommandTestGeneration || aCommandTestClient !== client)
                    return@runOnUiThread
                aCommandTestRunning = false
                aCommandTestClient = null
                aCommandResult.text = outcome.fold({ response ->
                    "解析成功；未执行任何手机操作。\ntask_id：${response.taskId}\n" + describeACommand(response.intent, apps)
                }, { error ->
                    val reason = when (error) {
                        is ACommandHttpException -> when (error.statusCode) {
                            401, 403 -> "代理鉴权失败，请核对自建代理访问令牌"
                            404 -> "代理未提供 /v1/commands/interpret，请由后端接入 A 指令解析接口"
                            else -> "代理 HTTP ${error.statusCode}，请检查后端日志"
                        }
                        is IllegalArgumentException -> error.message ?: "响应未通过 A 指令协议校验"
                        else -> "网络请求失败、超时或应用清单不可用，请检查后重试"
                    }
                    "解析测试失败；未执行任何手机操作。\ntask_id：$taskId\n$reason"
                })
            }
        }
    }
    private fun describeACommand(intent: ACommandIntent, apps: List<ACommandApp>): String = when (intent) {
        is ACommandIntent.OpenApp -> "打开应用：${apps.firstOrNull { it.id == intent.target }?.label ?: intent.target}"
        is ACommandIntent.CallContact -> "拨打已保存联系人：${intent.target}"
        is ACommandIntent.Navigate -> "系统导航：${intent.target.wire}"
        is ACommandIntent.ExitApp -> "退出当前应用：${intent.target}"
        is ACommandIntent.ScreenAction -> "页面动作：${intent.action.wire}，控件：${intent.target}"
        is ACommandIntent.Vision -> "拍照问答模式：${intent.mode.name}"
        is ACommandIntent.Clarify -> "需要澄清：${intent.message}"
        is ACommandIntent.Unsupported -> "暂不支持：${intent.message}"
    }
    private fun cancelACommandTest() {
        aCommandTestGeneration++
        aCommandTestClient?.close()
        aCommandTestClient = null
        if (aCommandTestRunning && ::aCommandResult.isInitialized) aCommandResult.text = "A 解析测试已取消；未执行手机操作"
        aCommandTestRunning = false
    }
    private fun saveOcr(): Boolean {
        val value = ocrUrl.text.toString().trim()
        val secret = ocrToken.text.toString().trim()
        val error = runCatching {
            OcrProtocol.endpoint(value, ocrLan.isChecked, BuildConfig.DEBUG)
            require(secret.isNotBlank() && !secret.contains('\r') && !secret.contains('\n')) { "请填写不含换行的 OCR 令牌" }
        }.exceptionOrNull()
        if (error != null) { notice(error.message ?: "OCR 配置错误"); return false }
        AssistRuntime.configureOcr(value, secret, ocrLan.isChecked)
        return true
    }
    private fun saveMedicine(): Boolean {
        val result = runCatching {
            AssistRuntime.configureMedicineServer(medicineEnabled.isChecked, medicineUrl.text.toString(),
                medicineToken.text.toString(), medicineLan.isChecked, medicineUseB.isChecked)
        }
        if (result.isFailure) { notice(result.exceptionOrNull()?.message ?: "交接服务配置错误"); return false }
        medicineHealthClient?.cancel(); medicineHealthClient = null
        if (medicineUseB.isChecked) {
            agentEnabled.isChecked = AssistRuntime.bAgentEnabled
            agentUrl.setText(AssistRuntime.bAgentUrl)
            agentToken.setText(AssistRuntime.bAgentToken)
            agentModel.setText(AssistRuntime.bAgentModel)
            agentLan.isChecked = AssistRuntime.bAgentLan
        }
        refresh()
        return true
    }
    @Deprecated("Uses the platform document picker on supported Android versions")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 73 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        if (pickerGeneration < 0) { notice("页面已重建，请重新选择照片"); return }
        AssistRuntime.selectedOcrImage(uri, pickerQuestion, pickerGeneration)
        pickerGeneration = -1L
    }
    override fun onDestroy() {
        cancelACommandTest(); aCommandTestWorker.shutdownNow()
        healthClient.cancelAll(); medicineHealthClient?.cancel(); medicineHealthClient = null; healthWorker.shutdownNow()
        super.onDestroy()
    }
    private fun refresh() {
        if (!::status.isInitialized) return
        status.text = "${AssistRuntime.bleMessage}\n无障碍：${if (AssistRuntime.accessibilityConnected) "已开启" else "未开启"}\n本次观察：${if (AssistRuntime.screenAllowed) "已开始" else "未开始"}\n${AssistRuntime.statusMessage}\n\n联调状态：\nA task：${AssistRuntime.session.aTask ?: "无"}\nB：${AssistRuntime.session.bState}\nprompt_id：${AssistRuntime.session.promptId ?: "无"}"
    }
    private fun notice(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    override fun onResume() { super.onResume(); resumed = true; AssistRuntime.addListener(observer); refresh(); OcrResultDialog.attach(this); AContactDialog.attach(this) }
    override fun onPause() { resumed = false; cancelACommandTest(); AContactDialog.detach(this); OcrResultDialog.detach(this); AssistRuntime.removeListener(observer); super.onPause() }
}
