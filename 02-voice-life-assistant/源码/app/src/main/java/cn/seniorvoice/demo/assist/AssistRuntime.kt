package cn.seniorvoice.demo.assist

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import cn.seniorvoice.demo.*
import cn.seniorvoice.demo.appcontrol.InstalledAppDirectory
import cn.seniorvoice.demo.appcontrol.InstalledAppMatcher
import cn.seniorvoice.demo.appcontrol.InstalledApp
import cn.seniorvoice.demo.contacts.AndroidContactPronouncer
import cn.seniorvoice.demo.contacts.ContactMatcher
import cn.seniorvoice.demo.nextaction.NextActionRuntime
import cn.seniorvoice.demo.nextaction.observation.NextActionAccessibilityService
import cn.seniorvoice.demo.setup.PermissionRecovery
import cn.seniorvoice.demo.setup.PermissionRequirements
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

data class AssistUpdate(val kind: String, val message: String, val text: String = "", val device: BluetoothDevice? = null)

/** Process scope, independent of HomeActivity; all mutable routing state belongs to main. */
object AssistRuntime {
    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(AssistUpdate) -> Unit>()
    private val network = Executors.newSingleThreadExecutor()
    private val audioStorage = Executors.newSingleThreadExecutor()
    private val commandWorker = Executors.newSingleThreadExecutor()
    private val dedupLock = Any()
    private val reservedRecordings = mutableSetOf<String>() // main only; covers the asynchronous durable write
    private val aVision = VisionClient()
    private val ocr = OcrWorkflow { main.post(it) }
    private val unifiedVision = VisionWorkflow { main.post(it) }
    private val medicineVision = MedicineVisionWorkflow { main.post(it) }
    private val taskPhotos = TaskPhotoDownloader()
    private val contactMatcher by lazy { ContactMatcher(AndroidContactPronouncer()) }
    private data class CallChoice(val name: String, val number: String)
    private data class CallOffer(val choices: List<CallChoice>, val expiresAt: Long, val confirmationId: String)
    private var callOffer: CallOffer? = null
    private data class CommandOffer(val intent: ACommandIntent, val request: ACommandRequest,
        val apps: Map<String, InstalledApp>, val contacts: Map<String, CallChoice>,
        val captured: ACommandScreenController.Captured?, val expiresAt: Long,
        val gate: ACommandExecutionGate, val confirmationId: String)
    private data class CommandClarification(val utterance: String, val question: String, val expiresAt: Long,
        val confirmationId: String)
    private var commandOffer: CommandOffer? = null
    private var commandClarification: CommandClarification? = null
    private var commandClient: ACommandApiClient? = null
    private var visionBinding: JSONObject? = null
    private var aRecording: RecordingEnvelope? = null
    private var ocrBinding: JSONObject? = null
    val session = AssistSession()
    lateinit var ble: BleClient; private set
    lateinit var hardwareConnection: HardwareConnectionController; private set
    private lateinit var speech: OfflineSpeechClient
    private lateinit var speaker: AssistantSpeaker
    private lateinit var transfer: BleAudioTransfer
    private lateinit var records: BackendSync
    private var accessibility: NextActionAccessibilityService? = null
    private var bController: BAssistController? = null
    private var pending: RecordingEnvelope? = null
    private var raw: RecordingEnvelope? = null
    private var framed: RecordingEnvelope? = null
    private var metadataRequired = false
    private var rejectNextRaw = false
    private var firmwareSession: String? = null
    private var recognitionEpoch = 0L
    private var framedStartedAt = 0L
    private var lastFramedProgressAt = 0L
    private var aStartedAt = 0L
    private var connectionEpoch = 0L
    private var textConnectionId = UUID.randomUUID().toString()
    private val textFrames = BleTextFrameSession({ frame ->
        if (frame.text.isBlank()) update("status", "A 文字帧为空，未执行；请重新录音")
        else {
            update("hardware_transcript", "A 硬件识别：${frame.text}", frame.text)
            command("text-v1-$textConnectionId-${frame.sequence}", frame.text, "ble_text_v1")
        }
    }, { frame ->
        records.record("hardware_status", "text-v1-$textConnectionId-${frame.sequence}", "ble_text_v1", frame.text, "status")
        update("status", "硬件状态：${frame.text}")
    }, { frame -> update("status", "已忽略重复文字帧（序号 ${frame.sequence}），未重复执行") })
    private var aQuestion: String? = null
    private var aEventId: String? = null
    private var awaitingFrame: String? = null
    private var frameBusy = false
    private var recognizingButton: String? = null
    private var recordingBToken: Long? = null
    var homeForeground = false
    var isRecognizing = false; private set
    var bleMessage = "未连接硬件"; private set
    var statusMessage = "等待 A 指令；B 可在设置中开启"; private set
    var lastTranscript = ""; private set
    var voiceStage = "idle"; private set
    var voiceProgressText = "等待硬件录音"; private set
    var voicePercent: Int? = null; private set
    var voiceReceivedBytes = 0L; private set
    var voiceTotalBytes = 0L; private set
    var voiceBytesPerSecond = 0L; private set
    var asrReady = false; private set
    var asrStatus = "后台连接开启后准备离线语音模型"; private set
    var backgroundServiceRunning = false; private set
    private var warmingSpeech = false
    private var sessionConsent = false
    private val prefs get() = app.getSharedPreferences("assist_settings", Context.MODE_PRIVATE)
    private val settings get() = app.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    val initialized get() = ::app.isInitialized
    val enabled get() = initialized && prefs.getBoolean("b_enabled", false)
    val screenAllowed get() = enabled && sessionConsent && accessibility != null
    val consentGranted get() = sessionConsent
    val baseUrl get() = if (initialized) prefs.getString("base_url", VisionClient.DEFAULT_BASE).orEmpty() else VisionClient.DEFAULT_BASE
    val apiToken get() = if (initialized) prefs.getString("api_token", "").orEmpty() else ""
    val ocrUrl get() = if (initialized) prefs.getString("ocr_url", "").orEmpty() else ""
    val ocrToken get() = if (initialized) prefs.getString("ocr_token", "").orEmpty() else ""
    val ocrLan get() = BuildConfig.DEBUG && initialized && prefs.getBoolean("ocr_lan", false)
    val unifiedVisionEnabled get() = initialized && prefs.getBoolean("unified_vision_enabled", false)
    val unifiedVisionUrl get() = if (initialized) prefs.getString("unified_vision_url", ServerConfig.DEFAULT_BASE).orEmpty() else ServerConfig.DEFAULT_BASE
    val unifiedVisionToken get() = if (initialized) prefs.getString("unified_vision_token", "").orEmpty() else ""
    val unifiedVisionLan get() = BuildConfig.DEBUG && initialized && prefs.getBoolean("unified_vision_lan", false)
    val medicineEnabled get() = initialized && prefs.getBoolean("medicine_enabled", false)
    val medicineUrl get() = if (initialized) prefs.getString("medicine_url", "").orEmpty() else ""
    val medicineToken get() = if (initialized) prefs.getString("medicine_token", "").orEmpty() else ""
    val medicineLan get() = BuildConfig.DEBUG && initialized && prefs.getBoolean("medicine_lan", false)
    val bAgentEnabled get() = initialized && prefs.getBoolean("b_agent_enabled", false)
    val bAgentUrl get() = if (initialized) prefs.getString("b_agent_url", "").orEmpty() else ""
    val bAgentModel get() = if (initialized) prefs.getString("b_agent_model", "Qwen/Qwen3-VL-8B-Instruct").orEmpty() else "Qwen/Qwen3-VL-8B-Instruct"
    val bAgentToken get() = if (initialized) prefs.getString("b_agent_token", "").orEmpty() else ""
    val bAgentLan get() = BuildConfig.DEBUG && initialized && prefs.getBoolean("b_agent_lan", false)
    val aCommandEnabled get() = initialized && prefs.getBoolean("a_command_enabled", false)
    val aCommandUrl get() = if (initialized) prefs.getString("a_command_url", "").orEmpty() else ""
    val aCommandToken get() = if (initialized) prefs.getString("a_command_token", "").orEmpty() else ""
    val aCommandLan get() = BuildConfig.DEBUG && initialized && prefs.getBoolean("a_command_lan", false)
    val aCommandScreenEnabled get() = initialized && prefs.getBoolean("a_command_screen", false)
    val aScreenAllowed get() = aCommandEnabled && aCommandScreenEnabled && session.aTask != null && accessibility != null
    val dwellSeconds get() = if (initialized) prefs.getInt("dwell_seconds", 25).coerceIn(10, 120) else 25
    val accessibilityConnected get() = accessibility != null
    val ttsEnabled get() = initialized && prefs.getBoolean("tts_enabled", true)
    fun configureTts(enabled: Boolean) { prefs.edit().putBoolean("tts_enabled", enabled).apply(); if (!enabled) speaker.stop() }
    fun testTts() {
        if (session.aTask != null || session.bState != AssistSession.BState.OBSERVING) {
            update("tts_status", "请先结束当前 A/B 任务，再测试朗读"); return
        }
        if (!ttsEnabled) { update("tts_status", "请先勾选手机中文 TTS 朗读"); return }
        speaker.speak("语音生活助手朗读测试。识别结果将由这部手机为您朗读。")
        update("tts_status", "已请求中文试音；请确认手机实际发出声音")
    }
    fun speakB(text: String) { if (ttsEnabled && session.aTask == null) speaker.speak(text) }
    fun stopSpeaking() { if (::speaker.isInitialized) speaker.stop() }
    private fun now() = SystemClock.elapsedRealtime()
    private val aTimeout = Runnable {
        session.aTask?.let { task -> result(task, "timeout", "A 等待录音、照片或服务超时，请重新发起") }
    }
    private val transferTimeout = Runnable {
        transfer.abort(); framed = null; pending = null; raw = null
        rejectNextRaw = true
        if (session.aTask != null) result(session.aTask!!, "audio_timeout", "录音传输超时，请重新录音")
        else bController?.cancel("B 录音传输超时")
    }

    fun initialize(context: Context) {
        if (initialized) return
        app = context.applicationContext
        IntegrationLog.initialize(app.filesDir)
        IntegrationLog.event("runtime", "started")
        speaker = AssistantSpeaker(app) { update("tts_status", "手机中文语音引擎不可用，请在系统安装/启用中文 TTS；文字结果仍可查看") }
        NextActionRuntime.initialize(app)
        speech = OfflineSpeechClient(app); transfer = BleAudioTransfer(app.cacheDir)
        records = BackendSync(app, {
            BackendSettings(settings.getString("endpoint", "").orEmpty(), settings.getString("token", "").orEmpty(), settings.getString("device_id", "").orEmpty())
        }, { message -> main.post { update("sync", message) } }, { main.post { update("records", "记录已更新") } })
        ble = BleClient(app, { message ->
            bleMessage = message
            update("ble", message)
        },
            { device, name -> publish(AssistUpdate("device", name, device = device)) }, ::receiveEvent, ::receiveRaw,
            onReceiveProgress = {
                if ((transfer.isReceiving && framed?.let { active(it, recordingBToken) } == true) ||
                    raw?.let { active(it, recordingBToken) } == true) {
                    main.removeCallbacks(transferTimeout); main.postDelayed(transferTimeout, 30000)
                }
            }, onDisconnect = ::disconnected, onWavStarted = ::rawStarted, onTextFrame = textFrames::accept,
            onReady = { hardwareConnection.ready(); IntegrationLog.event("BLE", "subscribed"); warmUpSpeech() },
            onTransferProgress = ::receiveProgress,
            onScanFinished = { hardwareConnection.scanningStopped() })
        hardwareConnection = HardwareConnectionController(app, ble) { bleMessage = it; update("ble", it) }
    }

    fun ensureService(): Boolean {
        if (!PermissionRequirements.bluetoothGranted(app)) {
            permissionIssue("蓝牙连接权限尚未开启，点击“一键修复权限”即可继续设置。")
            return false
        }
        hardwareConnection.enable()
        return runCatching {
            app.startForegroundService(Intent(app, VoiceForegroundService::class.java)); true
        }.getOrElse { permissionIssue("后台连接没有启动，点击“一键修复权限”检查蓝牙、通知和后台运行设置。让系统设置完成后，助手会尝试恢复连接。"); false }
    }
    fun serviceStarted() {
        backgroundServiceRunning = true
        hardwareConnection.serviceStarted()
        warmUpSpeech()
        publish(AssistUpdate("connection", "后台连接已开启"))
    }
    fun serviceStopped() {
        backgroundServiceRunning = false
        sessionConsent = false
        hardwareConnection.serviceStopped()
        cancelAll("后台连接已停止")
        publish(AssistUpdate("connection", "后台连接已停止"))
    }
    fun pauseHardware() {
        sessionConsent = false
        hardwareConnection.pause()
        cancelAll("后台连接已暂停")
        app.stopService(Intent(app, VoiceForegroundService::class.java))
        publish(AssistUpdate("connection", "后台连接已暂停"))
    }
    private fun warmUpSpeech() {
        if (asrReady || warmingSpeech) return
        warmingSpeech = true
        val started = now()
        IntegrationLog.event("ASR_MODEL", "preparing")
        speech.warmUp({ message ->
            asrStatus = message
            publish(AssistUpdate("model_status", message))
            if (isRecognizing && voiceStage == "model") voiceProgress("model", message)
        }) { outcome ->
            warmingSpeech = false
            asrReady = outcome.isSuccess
            asrStatus = if (asrReady) "离线语音模型已就绪" else "语音模型准备失败，可在重新连接时重试"
            IntegrationLog.event("ASR_MODEL", if (asrReady) "ready" else "failed", elapsedMs = now() - started)
            publish(AssistUpdate("model_status", asrStatus))
            if (isRecognizing && voiceStage == "model") voiceProgress("model",
                if (asrReady) "离线语音模型已就绪，正在开始本次识别" else "正在为本次录音重试准备离线语音模型")
        }
    }
    private fun voiceProgress(stage: String, message: String, percent: Int? = null) {
        voiceStage = stage
        voiceProgressText = message
        voicePercent = percent
        publish(AssistUpdate("voice_progress", message))
    }
    private fun receiveProgress(progress: BleReceiveProgress) {
        val envelope = raw ?: return
        recordingProgress(envelope, progress)
    }
    private fun recordingProgress(envelope: RecordingEnvelope, progress: BleReceiveProgress) {
        // Old B bytes may still arrive after A preempts. They cannot own A's progress or result.
        if (!active(envelope, recordingBToken)) return
        voiceReceivedBytes = progress.receivedBytes
        voiceTotalBytes = progress.totalBytes ?: 0L
        voiceBytesPerSecond = if (progress.elapsedMs > 0) progress.receivedBytes * 1000 / progress.elapsedMs else 0
        if (!isRecognizing) {
            val percent = progress.totalBytes?.takeIf { it > 0 }?.let { (progress.receivedBytes * 100 / it).toInt().coerceIn(0, 100) }
            val size = if (voiceTotalBytes > 0) "${voiceReceivedBytes / 1024}/${voiceTotalBytes / 1024} KB" else "${voiceReceivedBytes / 1024} KB"
            val speed = if (voiceBytesPerSecond > 0) " · ${voiceBytesPerSecond / 1024} KB/s" else ""
            voiceProgress("receiving", if (progress.complete) "${envelope.button} 录音已收齐，准备校验文件" else "正在接收 ${envelope.button} 硬件录音 $size$speed", percent)
        }
        if (progress.complete) IntegrationLog.event("BLE_RX", "audio_received", recordingIds(envelope), bytes = progress.receivedBytes,
            elapsedMs = progress.elapsedMs, firstBytesHex = progress.firstBytesHex, bytesPerSecond = voiceBytesPerSecond)
    }
    private fun framedProgress(envelope: RecordingEnvelope, complete: Boolean = false, finalProgress: BleReceiveProgress? = null) {
        val time = now()
        if (complete || lastFramedProgressAt == 0L || time - lastFramedProgressAt >= 250) {
            lastFramedProgressAt = time
            val progress = finalProgress ?: transfer.progress(time - framedStartedAt)
            recordingProgress(envelope, progress.copy(complete = complete))
        }
    }
    private fun recordingIds(envelope: RecordingEnvelope) = mapOf("session_id" to envelope.sessionId,
        "event_id" to envelope.eventId, "task_id" to envelope.taskId, "prompt_id" to envelope.promptId, "button" to envelope.button)
    fun addListener(listener: (AssistUpdate) -> Unit) { listeners.add(listener); listener(AssistUpdate("ble", bleMessage)); listener(AssistUpdate("status", statusMessage)) }
    fun removeListener(listener: (AssistUpdate) -> Unit) { listeners.remove(listener) }
    private fun publish(value: AssistUpdate) { listeners.forEach { runCatching { it(value) } } }
    fun update(kind: String, message: String, text: String = "") {
        if (kind in setOf("status", "a_result") || (kind == "b_result" && session.aTask == null)) statusMessage = message
        if (kind in setOf("transcript", "hardware_transcript")) lastTranscript = text.ifBlank { message }
        if (kind == "audio") {
            val stage = if (message.contains("模型") || message.contains("解包")) "model" else "transcribing"
            val percent = Regex("(\\d{1,3})%").find(message)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, 100)
            voiceProgress(stage, message, percent)
        } else if (kind == "b_result" && session.aTask == null) voiceProgress("done", message)
        else if (kind == "b_status" && session.aTask == null) {
            when (session.bState) {
                AssistSession.BState.WAITING -> voiceProgress("waiting_confirm", message)
                AssistSession.BState.CHECKING, AssistSession.BState.THINKING -> voiceProgress("vision", message)
                AssistSession.BState.OBSERVING -> if (voiceStage in setOf("recording", "receiving", "validating", "model", "transcribing", "vision", "waiting_confirm")) {
                    val failed = listOf("超时", "失败", "不完整", "错误", "不可用", "没听清").any(message::contains)
                    voiceProgress(if (failed) "error" else "done", message)
                }
                else -> Unit
            }
        }
        publish(AssistUpdate(kind, message, text))
    }
    fun send(value: JSONObject): Boolean {
        val line = value.toString()
        val queued = initialized && ble.send(line)
        IntegrationLog.event("BLE_TX", if (queued) "queued" else "not_queued", mapOf(
            "event_id" to value.optString("event_id"), "task_id" to value.optString("task_id"),
            "prompt_id" to value.optString("prompt_id")), bytes = line.toByteArray(Charsets.UTF_8).size.toLong())
        return queued
    }
    fun requestTextResend() {
        val queued = initialized && ble.send("RESEND")
        update("status", if (queued) "已排队发送 RESEND 请求；已处理的序号不会重复执行" else "无法请求重发，请先连接支持 WRITE 的蓝牙通道")
    }
    fun sendResult(id: String, status: String, message: String, envelope: RecordingEnvelope? = null) {
        send((envelope?.correlation() ?: JSONObject()).put("v", 2).put("type", "result").put("event_id", id)
            .put("status", status).put("reply", message.take(1800)))
    }
    private fun ack(id: String, status: String, message: String = "") {
        send(JSONObject().put("v", 2).put("type", "ack").put("event_id", id).put("status", status).put("reply", message))
    }

    fun configure(enabled: Boolean, url: String, token: String, dwell: Int) {
        cancelAll("配置已修改")
        prefs.edit().putBoolean("b_enabled", enabled).putString("base_url", url.trimEnd('/'))
            .putString("api_token", token).putInt("dwell_seconds", dwell.coerceIn(10, 120)).apply()
        sessionConsent = false; bController?.reconfigure()
    }
    fun configureOcr(url: String, token: String, lan: Boolean) {
        OcrProtocol.endpoint(url, lan, BuildConfig.DEBUG)
        if (ocrUrl == url.trim().trimEnd('/') && ocrToken == token.trim() && ocrLan == lan) return
        cancelAll("OCR 配置已修改")
        prefs.edit().putString("ocr_url", url.trim().trimEnd('/')).putString("ocr_token", token.trim())
            .putBoolean("ocr_lan", lan).apply()
    }
    fun configureUnifiedVision(enabled: Boolean, url: String, token: String, lan: Boolean) {
        if (enabled) ServerConfig.parse(url, lan, BuildConfig.DEBUG)
        require(!token.contains('\r') && !token.contains('\n')) { "令牌不能包含换行" }
        cancelAll("统一视觉配置已修改")
        prefs.edit().putBoolean("unified_vision_enabled", enabled).putString("unified_vision_url", url.trim().trimEnd('/'))
            .putString("unified_vision_token", token.trim()).putBoolean("unified_vision_lan", lan && BuildConfig.DEBUG).apply()
    }
    fun configureMedicineServer(enabled: Boolean, url: String, token: String, lan: Boolean, useForB: Boolean) {
        val root = url.trim().trimEnd('/')
        if (enabled || useForB) {
            val config = ServerConfig.parse(root, lan, BuildConfig.DEBUG)
            require(config.base.path in setOf("", "/")) { "请填写新服务根地址，不包含接口路径" }
            require(config.base.host !in setOf("127.0.0.1", "localhost")) { "手机请填写电脑局域网 IPv4，不能填写 127.0.0.1" }
            require(token.isNotBlank() && token.none { it.isISOControl() }) { "请填写服务器访问令牌，不能包含控制字符" }
        }
        cancelAll("交接服务配置已修改")
        val edit = prefs.edit().putBoolean("medicine_enabled", enabled).putString("medicine_url", root)
            .putString("medicine_token", token.trim()).putBoolean("medicine_lan", lan && BuildConfig.DEBUG)
        if (useForB) edit.putBoolean("b_agent_enabled", true).putString("b_agent_url", "$root/v1")
            .putString("b_agent_token", token.trim()).putString("b_agent_model", "Qwen/Qwen3-VL-8B-Instruct")
            .putBoolean("b_agent_lan", lan && BuildConfig.DEBUG)
        edit.apply()
        bController?.reconfigure()
    }
    fun configureBAgent(enabled: Boolean, url: String, model: String, token: String, lan: Boolean) {
        if (enabled) BAgentConfig.validate(url, model, token, lan, BuildConfig.DEBUG)
        cancelB("B Agent 配置已修改")
        prefs.edit().putBoolean("b_agent_enabled", enabled).putString("b_agent_url", url.trim().trimEnd('/'))
            .putString("b_agent_model", model.trim()).putString("b_agent_token", token.trim())
            .putBoolean("b_agent_lan", lan && BuildConfig.DEBUG).apply()
        bController?.reconfigure()
    }
    fun configureACommand(enabled: Boolean, url: String, token: String, lan: Boolean, screen: Boolean) {
        if (enabled) ACommandApiClient(url, token, lan, BuildConfig.DEBUG)
        if (aCommandEnabled == enabled && aCommandUrl == url.trim().trimEnd('/') && aCommandToken == token.trim() &&
            aCommandLan == (lan && BuildConfig.DEBUG) && aCommandScreenEnabled == screen) return
        cancelAll("A 智能指令配置已修改")
        prefs.edit().putBoolean("a_command_enabled", enabled).putString("a_command_url", url.trim().trimEnd('/'))
            .putString("a_command_token", token.trim()).putBoolean("a_command_lan", lan && BuildConfig.DEBUG)
            .putBoolean("a_command_screen", screen).apply()
    }
    fun setConsent(allowed: Boolean) {
        accessibility?.history?.clear()
        sessionConsent = allowed && enabled
        bController?.reconfigure()
        if (sessionConsent) session.clearB(now(), 0)
        update("b_status", if (screenAllowed) "B 页面观察已开启；A 始终优先" else "B 页面观察已暂停")
    }
    fun attach(service: NextActionAccessibilityService) {
        initialize(service)
        if (accessibility === service) return
        bController?.close(); accessibility = service; bController = BAssistController(service)
    }
    fun detach(service: NextActionAccessibilityService) {
        if (accessibility !== service) return
        bController?.close(); bController = null; accessibility = null; sessionConsent = false
        cancelAll("无障碍服务已断开")
    }
    fun pageEvent(event: android.view.accessibility.AccessibilityEvent) { bController?.event(event) }
    fun cancelB(reason: String) { bController?.cancel(reason) ?: session.clearB(now()) }
    fun cancelBRecording() {
        val hadBRecording = recognizingButton == "B" || pending?.button == "B" || framed?.button == "B" || raw?.button == "B"
        if (recognizingButton == "B") { recognitionEpoch++; speech.cancel(); isRecognizing = false; recognizingButton = null }
        if (pending?.button == "B") { pending = null; rejectNextRaw = true }
        if (framed?.button == "B") { transfer.abort(); framed = null }
        // An in-flight raw B file retains its B envelope so it is discarded on completion.
        recordingBToken = null
        if (hadBRecording) main.removeCallbacks(transferTimeout)
    }
    private fun beginA(task: String, eventId: String = task, replyPromptId: String? = null) {
        // A new recording may answer only the exact prompt echoed by firmware.
        if (replyPromptId == null || commandOffer?.confirmationId != replyPromptId) {
            commandOffer?.gate?.invalidate(); commandOffer = null
        }
        if (replyPromptId == null || callOffer?.confirmationId != replyPromptId) callOffer = null
        if (replyPromptId == null || commandClarification?.confirmationId != replyPromptId) commandClarification = null
        stopSpeaking()
        IntegrationLog.event("A", "started", mapOf("task_id" to task, "event_id" to eventId))
        OcrResultDialog.clear()
        AContactDialog.clear()
        commandClient?.cancelAll(); commandClient = null
        val oldPrompt = session.promptId
        recognitionEpoch++; speech.cancel(); isRecognizing = false; recognizingButton = null
        aVision.cancelAll(); awaitingFrame = null; aQuestion = null; frameBusy = false
        ocr.cancel(); ocrBinding = null
        unifiedVision.cancel(); medicineVision.cancel(); taskPhotos.cancel(); visionBinding = null; aRecording = null
        transfer.abort(); framed = null; pending = null; recordingBToken = null
        main.removeCallbacks(transferTimeout)
        session.beginA(task, now())
        aStartedAt = now()
        aEventId = eventId
        bController?.preempt(); NextActionRuntime.cancel("A 已启动，清理旧预测")
        if (oldPrompt != null) send(JSONObject().put("v", 2).put("type", "assist_cancel").put("prompt_id", oldPrompt).put("reason", "a_priority"))
        main.removeCallbacks(aTimeout); main.postDelayed(aTimeout, 120000)
        voiceReceivedBytes = 0; voiceTotalBytes = 0; voiceBytesPerSecond = 0
        voiceProgress("recording", "A 已开始，等待本次指令")
        update("status", "A 已开始，B 已暂停并清理")
    }

    private fun receiveEvent(event: DeviceEvent) {
        try {
            when (event.type) {
                "recording_meta", "button_start" -> metadata(event)
                "button_end" -> ack(event.id, "waiting_audio", "请发送元信息绑定的完整 WAV 或 PCM 分块")
                "audio_start", "audio_ready" -> startFramed(event)
                "audio_chunk" -> {
                    val envelope = framed ?: error("没有对应录音元信息")
                    require(active(envelope, recordingBToken)) { "录音所属任务已失效" }
                    transfer.append(event)
                    framedProgress(envelope)
                    // No per-chunk ACK: firmware must not wait for one between audio chunks.
                    main.removeCallbacks(transferTimeout); main.postDelayed(transferTimeout, 30000)
                }
                "audio_end" -> {
                    val envelope = framed ?: error("没有对应录音元信息")
                    val progress = transfer.progress(now() - framedStartedAt)
                    val file = transfer.finish(event)
                    framedProgress(envelope, complete = true, finalProgress = progress)
                    framed = null; receiveFile(envelope, file)
                }
                "ping" -> sendResult(event.id, "pong", "手机回传成功")
                "command" -> {
                    require(event.data.optString("source_button", "A") == "A") { "B 回答必须带录音元信息并发送音频" }
                    command(event.id, event.text, "ble")
                }
                "image_ready" -> imageReady(event)
                "cancel" -> {
                    val task = event.data.optString("task_id")
                    val prompt = event.data.optString("prompt_id")
                    when {
                        task.isNotBlank() && task == session.aTask -> cancelAll("硬件取消了当前 A 任务")
                        session.aTask == null && prompt.isNotBlank() && prompt == session.promptId -> cancelB("硬件取消了当前 B 询问")
                        else -> ack(event.id, "stale", "取消消息不属于当前任务，已忽略")
                    }
                }
                "complete" -> ack(event.id, "use_button_metadata", "A/B 请发送 source_button 和录音元信息；COMPLETE 不执行旧预测")
            }
        } catch (error: Exception) {
            IntegrationLog.event("BLE", "rejected", mapOf("event_id" to event.id))
            if (event.type in setOf("recording_meta", "button_start", "audio_start", "audio_ready") && pending == null && raw == null) rejectNextRaw = true
            val failedRecording = (framed ?: pending)?.takeIf { it.eventId == event.id }
            if (event.type.startsWith("audio_") && failedRecording != null) {
                transfer.abort(); framed = null; pending = null; rejectNextRaw = true
                main.removeCallbacks(transferTimeout)
                if (active(failedRecording, recordingBToken)) {
                    if (failedRecording.button == "A") result(failedRecording.taskId, "audio_invalid", "录音不完整或格式错误，请重新按 A 录音")
                    else bController?.cancel("B 录音不完整或格式错误，本次未执行")
                }
            }
            sendResult(event.id, "rejected", error.message ?: "消息未通过校验", failedRecording)
            val kind = if (session.aTask != null && (failedRecording?.button == "B" || event.data.optString("source_button") == "B" ||
                    (event.type.startsWith("audio_") && failedRecording == null))) "ble_protocol" else "status"
            update(kind, "消息未处理：${error.message ?: "格式错误"}")
        }
    }

    private fun seen(envelope: RecordingEnvelope): Boolean {
        val list = JSONArray(settings.getString("assist_seen", "[]"))
        val key = recordingKey(envelope)
        return key in reservedRecordings || (0 until list.length()).any { list.optString(it) == key }
    }
    private fun recordingKey(envelope: RecordingEnvelope) = "${ble.peerId}:${envelope.sessionId}:${envelope.eventId}"
    private fun remember(envelope: RecordingEnvelope) = rememberKey(recordingKey(envelope))
    private fun rememberKey(key: String) = synchronized(dedupLock) {
        val list = JSONArray(settings.getString("assist_seen", "[]")); val next = JSONArray()
        for (i in maxOf(0, list.length() - 255) until list.length()) next.put(list.getString(i))
        if ((0 until list.length()).any { list.optString(it) == key }) return@synchronized
        next.put(key)
        check(settings.edit().putString("assist_seen", next.toString()).commit()) { "无法保存去重信息，本次停止" }
    }
    private fun metadata(event: DeviceEvent) {
        metadataRequired = true
        var envelope = RecordingEnvelope.parse(event)
        IntegrationLog.event("BLE", "recording_meta", mapOf("session_id" to envelope.sessionId, "event_id" to envelope.eventId,
            "task_id" to envelope.taskId, "prompt_id" to envelope.promptId, "button" to envelope.button), bytes = envelope.size)
        // A owns the runtime before B may change the firmware session or interrupt speech.
        require(envelope.button != "B" || (screenAllowed && session.aTask == null)) { "A 正在运行或 B 未开启，忽略 B" }
        require(!seen(envelope)) { "此录音已处理，不重复执行" }
        if (firmwareSession != null && firmwareSession != envelope.sessionId) {
            cancelAll("硬件会话变化，已清理旧任务")
        }
        firmwareSession = envelope.sessionId
        val sameRecording = pending?.let { it.eventId == envelope.eventId && it.taskId == envelope.taskId && it.button == envelope.button && it.promptId == envelope.promptId } == true
        if (!sameRecording) {
            if (envelope.button == "A") beginA(envelope.taskId, envelope.eventId, envelope.promptId)
            else {
                require(screenAllowed && session.aTask == null) { "A 正在运行或 B 未开启，忽略 B" }
                require(envelope.taskId == "b-${envelope.promptId}") { "B task_id 不属于当前问题" }
                recordingBToken = session.record(envelope.promptId!!) ?: error("B 问题已失效或尚未发起询问")
                bController?.recordingStarted()
                require(recordingBToken != null && session.current(recordingBToken!!) && session.bState == AssistSession.BState.RECORDING) {
                    "无法展示录音状态，本次 B 已取消"
                }
            }
        }
        stopSpeaking()
        envelope = envelope.copy(localGeneration = session.generation)
        if (envelope.button == "A") aRecording = envelope
        pending = envelope; rejectNextRaw = false
        voiceReceivedBytes = 0; voiceTotalBytes = envelope.expectedWavSize ?: 0; voiceBytesPerSecond = 0
        voiceProgress(if (event.type == "button_start") "recording" else "receiving",
            if (event.type == "button_start") "${envelope.button} 硬件正在录音，尚未开始传输" else "等待接收 ${envelope.button} 硬件录音")
        ack(event.id, "ready_for_audio", "元信息已绑定，请发送对应格式音频；不要交错发送其他录音")
        main.removeCallbacks(transferTimeout); main.postDelayed(transferTimeout, 90000)
    }

    private fun legacyEnvelope(id: String) = RecordingEnvelope(id, id, "A", "command", null, null, "legacy-$connectionEpoch")
    private fun startLegacy(id: String): RecordingEnvelope {
        val envelope = legacyEnvelope(id)
        beginA(envelope.taskId)
        return envelope.copy(localGeneration = session.generation)
    }
    private fun rawStarted() {
        raw = if (rejectNextRaw) null else pending.also { pending = null }
        if (raw?.format == "pcm_s16le") { raw = null; rejectNextRaw = true; update("status", "PCM 元信息必须配合 audio_start/chunk/end，不能混用裸 WAV") }
        if (raw == null && !metadataRequired && !rejectNextRaw) {
            raw = startLegacy("wav-${UUID.randomUUID()}")
        }
        if (raw?.let { active(it, recordingBToken) } == true) {
            voiceReceivedBytes = 0; voiceTotalBytes = 0; voiceBytesPerSecond = 0
            voiceProgress("receiving", "正在接收 ${raw!!.button} 硬件 WAV 录音", 0)
            main.removeCallbacks(transferTimeout); main.postDelayed(transferTimeout, 30000)
        }
    }
    private fun receiveRaw(file: File) {
        val envelope = raw; raw = null
        if (envelope == null) { file.delete(); update(if (session.aTask == null) "status" else "ble_protocol", "丢弃无有效元信息的 WAV；旧版裸 WAV 仅支持 A"); return }
        receiveFile(envelope, file)
    }
    private fun startFramed(event: DeviceEvent) {
        if (event.data.has("source_button")) metadata(event)
        require(event.data.optString("format") != "pcm_s16le" || pending != null) { "PCM 需要携带 A/B 录音元信息，不能猜测按钮来源" }
        val envelope = pending ?: if (!metadataRequired && !rejectNextRaw) startLegacy(event.id) else error("请先发送对应录音元信息")
        require(envelope.eventId == event.id) { "录音 ID 与元信息不一致" }
        require(envelope.format == event.data.optString("format") && (envelope.size == null || envelope.size == event.data.optLong("size"))) { "录音格式或长度与元信息不一致" }
        require(active(envelope, recordingBToken)) { "录音所属任务已失效" }
        transfer.start(event.copy(type = "audio_start")); framed = envelope
        framedStartedAt = now(); lastFramedProgressAt = 0L
        framedProgress(envelope)
        pending = null
        if (event.type == "audio_ready") {
            transfer.append(event.copy(type = "audio_chunk", data = JSONObject().put("index", 0).put("base64", event.data.getString("base64"))))
            val progress = transfer.progress(now() - framedStartedAt)
            val file = transfer.finish(event.copy(type = "audio_end", data = JSONObject().put("chunks", 1)))
            framedProgress(envelope, complete = true, finalProgress = progress)
            framed = null; receiveFile(envelope, file)
        } else { ack(event.id, "audio_started"); main.removeCallbacks(transferTimeout); main.postDelayed(transferTimeout, 30000) }
    }

    private fun active(envelope: RecordingEnvelope, token: Long?) = if (envelope.button == "A") session.aTask == envelope.taskId && session.generation == envelope.localGeneration
        else token != null && session.current(token) && session.promptId == envelope.promptId && session.bState == AssistSession.BState.RECORDING
    private fun receiveFile(envelope: RecordingEnvelope, file: File) {
        val bToken = recordingBToken
        if (!active(envelope, bToken) || seen(envelope) || (envelope.expectedWavSize != null && envelope.expectedWavSize != file.length())) {
            file.delete(); sendResult(envelope.eventId, "stale_or_invalid", "录音已失效、重复或长度不符，不执行", envelope)
            if (active(envelope, bToken)) {
                main.removeCallbacks(transferTimeout)
                if (envelope.button == "A") result(envelope.taskId, "audio_invalid", "录音重复或长度不符，请重新按 A 录音")
                else bController?.cancel("B 录音重复或长度不符，本次未执行")
            }
            return
        }
        main.removeCallbacks(transferTimeout)
        val epoch = ++recognitionEpoch; val connection = connectionEpoch
        val key = recordingKey(envelope)
        reservedRecordings.add(key)
        isRecognizing = true; recognizingButton = envelope.button
        val ids = recordingIds(envelope)
        val preparationStartedAt = now()
        ack(envelope.eventId, "audio_received", "完整录音已收到，正在校验并准备手机离线识别")
        voiceProgress("validating", "${envelope.button} 录音已收齐，正在校验并保存文件")
        IntegrationLog.event("AUDIO_FILE", "preparing", ids, bytes = file.length())
        audioStorage.execute {
            val prepared = runCatching {
                WavPcmReader.inspect(file)
                rememberKey(key) // Durable dedup must finish before an external action is allowed.
                AudioRecordingFiles.retain(file, File(app.filesDir, "last-recording"))
                settings.edit().putString("last_audio_button", envelope.button).apply()
            }
            IntegrationLog.event("AUDIO_FILE", if (prepared.isSuccess) "ready" else "failed", ids,
                bytes = file.length(), elapsedMs = now() - preparationStartedAt)
            main.post {
                reservedRecordings.remove(key)
                if (epoch != recognitionEpoch || connection != connectionEpoch || !active(envelope, bToken)) {
                    file.delete(); return@post
                }
                if (prepared.isFailure) {
                    file.delete(); isRecognizing = false; recognizingButton = null
                    if (envelope.button == "A") result(envelope.taskId, "audio_invalid", "录音格式无效、保存失败或去重信息未能保存，请重新录音")
                    else bController?.cancel("B 录音校验或保存失败，本次未执行")
                    return@post
                }
                val recognitionStartedAt = now()
                IntegrationLog.event("ASR", "started", ids, bytes = file.length())
                update("audio", if (asrReady) "${envelope.button} 录音已校验，开始手机离线转文字"
                    else "${envelope.button} 录音已校验，正在等待手机离线语音模型")
                // Reuse the received file. The ASR worker deletes it in finally, even after A preempts B.
                speech.recognize(file, { message ->
                    if (epoch == recognitionEpoch && connection == connectionEpoch && active(envelope, bToken)) update("audio", message)
                }, deleteAfter = true) { outcome ->
                    if (epoch != recognitionEpoch || connection != connectionEpoch || !active(envelope, bToken)) return@recognize
                    isRecognizing = false; recognizingButton = null
                    outcome.onSuccess { transcript ->
                        IntegrationLog.event("ASR", if (transcript.canExecute) "transcribed" else "low_confidence",
                            ids, elapsedMs = now() - recognitionStartedAt)
                        records.record("audio_transcription", envelope.eventId, "button_${envelope.button}", transcript.text, "transcribed",
                            JSONObject().put("task_id", envelope.taskId).put("session_id", envelope.sessionId)
                                .put("event_id", envelope.eventId).put("prompt_id", envelope.promptId ?: JSONObject.NULL))
                        update("transcript", "${envelope.button} 已识别：${transcript.text}", transcript.text)
                        if (!transcript.canExecute) {
                            if (envelope.button == "A") result(envelope.taskId, "low_confidence", "没有听清，请重新按 A 录音")
                            else bController?.cancel("没有听清，本次 B 结束，请等待下一次询问")
                        } else if (envelope.button == "A") executeA(envelope.taskId, transcript.text, envelope.eventId)
                        else {
                            voiceProgress("vision", "B 语音已识别，正在核对本次帮助")
                            bController?.reply(envelope, bToken!!, transcript.text)
                        }
                    }.onFailure {
                        IntegrationLog.event("ASR", "failed", ids, elapsedMs = now() - recognitionStartedAt)
                        if (envelope.button == "A") result(envelope.taskId, "transcription_failed", "录音识别失败，请重新录音")
                        else bController?.cancel("B 录音识别失败，已结束本次帮助")
                    }
                }
            }
        }
    }

    fun command(id: String, text: String, source: String = "debug") {
        val envelope = legacyEnvelope(id)
        if (seen(envelope)) { ack(id, "duplicate"); return }
        beginA(id)
        runCatching { remember(envelope) }.onFailure { result(id, "storage_failed", "无法保存去重信息，未执行"); return }
        records.record("command_received", id, source, text, "received")
        executeA(id, text, id)
    }
    fun retryA(file: File) {
        if (settings.getString("last_audio_button", "A") != "A") { update("status", "上一段是 B 回答，不能作为 A 指令重放"); return }
        val envelope = startLegacy("retry-${UUID.randomUUID()}")
        voiceProgress("validating", "正在读取上一段 A 录音")
        audioStorage.execute {
            val copy = runCatching {
                val temporary = File.createTempFile("retry-a-", ".wav", app.cacheDir)
                try { file.copyTo(temporary, overwrite = true); temporary }
                catch (error: Exception) { temporary.delete(); throw error }
            }
            main.post {
                if (!active(envelope, null)) { copy.getOrNull()?.delete(); return@post }
                copy.onSuccess { receiveFile(envelope, it) }
                    .onFailure { result(envelope.taskId, "audio_invalid", "上一段录音无法读取，请重新录音") }
            }
        }
    }
    private fun executeA(task: String, text: String, eventId: String) {
        if (session.aTask != task) return
        voiceProgress("executing", "已理解指令，正在处理")
        if (app.getSystemService(KeyguardManager::class.java).isKeyguardLocked) { result(task, "locked", "请解锁手机后重新发起 A"); return }
        val pendingCommand = commandOffer
        commandOffer = null
        if (pendingCommand != null && !confirmationMatches(pendingCommand.confirmationId)) pendingCommand.gate.invalidate()
        if (pendingCommand != null && confirmationMatches(pendingCommand.confirmationId)) {
            AContactDialog.clear(); bController?.clearAContactPrompt()
            when (ACommandConfirmation.reply(text)) {
                ACommandConfirmation.Reply.ACCEPT -> {
                    if (now() > pendingCommand.expiresAt) {
                        pendingCommand.gate.invalidate(); result(task, "command_expired", "操作确认已过期，请重新说出指令")
                    } else executeCommand(task, pendingCommand)
                    return
                }
                ACommandConfirmation.Reply.DECLINE -> {
                    pendingCommand.gate.invalidate(); result(task, "command_cancelled", "已取消，没有执行手机操作"); return
                }
                ACommandConfirmation.Reply.OTHER -> pendingCommand.gate.invalidate()
            }
        }
        val offered = callOffer?.takeIf { confirmationMatches(it.confirmationId) }
        callOffer = null
        if (offered != null) {
            AContactDialog.clear(); bController?.clearAContactPrompt()
            if (now() <= offered.expiresAt) {
                when (val reply = ContactCallConfirmation.reply(text, offered.choices.map { it.name })) {
                    is ContactCallConfirmation.Reply.Select -> { dialSavedContact(task, offered.choices[reply.index]); return }
                    ContactCallConfirmation.Reply.Decline -> { result(task, "call_cancelled", "已取消拨号"); return }
                    ContactCallConfirmation.Reply.Unclear -> if (CommandParser.parse(text) == CommandParser.Command.Unknown && !VisionApiClient.isVisualQuestion(text)) {
                        result(task, "call_unconfirmed", "没有确认联系人，已取消拨号；请重新按 A 说完整指令"); return
                    }
                }
            } else if (CommandParser.parse(text) == CommandParser.Command.Unknown && !VisionApiClient.isVisualQuestion(text)) {
                result(task, "call_expired", "联系人确认已过期，请重新按 A 说完整拨号指令"); return
            }
        }
        when (val command = CommandParser.parse(text)) {
            is CommandParser.Command.OpenApp -> {
                val directory = InstalledAppDirectory(app)
                when (val match = runCatching { directory.resolve(command.name) }.getOrNull()) {
                    is InstalledAppMatcher.Result.Matched -> {
                        val intent = directory.launchIntent(match.app)
                        if (intent == null) result(task, "unavailable", "目标应用没有启动入口")
                        else launch(task, intent, "已请求打开${match.app.label}")
                    }
                    is InstalledAppMatcher.Result.Ambiguous -> result(task, "app_ambiguous", "找到多个同名应用，请手动选择")
                    else -> interpretA(task, text, eventId)
                }
            }
            is CommandParser.Command.Call -> {
                val contacts = app.getSharedPreferences("saved_contacts", Context.MODE_PRIVATE).all.entries
                    .filter { it.key.startsWith("contact:") && it.value is String }
                    .map { ContactMatcher.Contact(it.key, it.key.removePrefix("contact:"), listOf(it.value as String)) }
                when (val match = contactMatcher.match(command.name, contacts)) {
                    is ContactMatcher.Result.Exact -> dialSavedContact(task, CallChoice(match.contact.name, match.phoneNumber))
                    is ContactMatcher.Result.Homophone -> offerContactCall(task, command.name, listOf(CallChoice(match.contact.name, match.phoneNumber)))
                    is ContactMatcher.Result.Ambiguous -> offerContactCall(task, command.name,
                        match.candidates.flatMap { contact -> contact.phoneNumbers.map { CallChoice(contact.name, it) } }.distinct())
                    ContactMatcher.Result.NotFound -> interpretA(task, text, eventId)
                }
            }
            CommandParser.Command.Unknown -> {
                val controller = accessibility?.let(::ACommandScreenController)
                val captured = if (aScreenAllowed) controller?.capture() else null
                val local = ACommandLocalParser.parse(text, controller?.currentPackage(), captured?.screen?.controls.orEmpty())
                if (local is ACommandIntent.Navigate) {
                    commandClarification = null
                    performNavigation(task, local.target); return
                }
                if (local != null) {
                    commandClarification = null
                    val screen = captured?.screen ?: (local as? ACommandIntent.ExitApp)?.let { ACommandScreen(it.target, emptyList()) }
                    offerCommand(task, local, ACommandRequest(task, text, emptyList(), emptyList(), screen), emptyMap(), emptyMap(), captured)
                    return
                }
                if (VisionApiClient.isVisualQuestion(text)) { commandClarification = null; startVisualQuestion(task, text, eventId); return }
                interpretA(task, text, eventId, captured)
            }
        }
    }

    /** Legacy recordings cannot confirm an old action; the on-screen confirmation remains available. */
    private fun confirmationMatches(expected: String): Boolean = ACommandConfirmation.matchesPrompt(expected, aRecording?.promptId)

    private fun startVisualQuestion(task: String, question: String, eventId: String, forcedMode: VisionMode? = null) {
        if (session.aTask != task) return
        if (question.length !in 1..1000) { result(task, "vision_failed", "问题过长，请重新按 A 简短描述要识读的内容"); return }
        if (medicineEnabled) { startMedicineVision(task, question, forcedMode = forcedMode); return }
        if (unifiedVisionEnabled) { startUnifiedVision(task, question, forcedMode = forcedMode); return }
        if (forcedMode == VisionMode.OCR || (forcedMode == null && OcrProtocol.supports(question))) { startOcr(task, question); return }
        aQuestion = question; awaitingFrame = task; frameBusy = false
        if (!send(JSONObject().put("v", 2).put("type", "capture").put("task_id", task).put("frame_id", "$task-frame-1")
                .put("source_button", "A").put("question", question))) {
            result(task, "vision_failed", "拍照消息未发送，请先连接可双向收发的眼镜"); return
        }
        aVisionProgress("A 正在等待眼镜照片：$question")
        main.removeCallbacks(aTimeout); main.postDelayed(aTimeout, 45000)
        records.record("vision_requested", eventId, "button_A", question, "waiting_frame",
            (aRecording?.correlation() ?: JSONObject()).put("task_id", task))
    }

    private fun commandText(value: String) = value.isNotBlank() && value.length <= 200 &&
        value.none { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }

    /** Only names and opaque local IDs leave the device. Phone numbers stay in the saved-contact map. */
    private fun interpretA(task: String, text: String, eventId: String, suppliedCapture: ACommandScreenController.Captured? = null) {
        if (!aCommandEnabled) {
            result(task, "command_not_configured", "这句话需要智能理解。请在设置中的“A 智能指令”连接团队云端服务；当前仍可说打开应用、拨打联系人、返回或回到桌面。")
            return
        }
        val previous = commandClarification?.takeIf { confirmationMatches(it.confirmationId) }
        commandClarification = null
        if (previous != null && now() <= previous.expiresAt && ACommandConfirmation.reply(text) == ACommandConfirmation.Reply.DECLINE) {
            result(task, "command_cancelled", "已结束本次指令"); return
        }
        val utterance = if (previous != null && now() <= previous.expiresAt)
            "原指令：${previous.utterance}；助手询问：${previous.question}；用户补充：$text" else text
        val generation = session.generation; val connection = connectionEpoch
        val captured = suppliedCapture ?: if (aScreenAllowed) accessibility?.let(::ACommandScreenController)?.capture() else null
        val appCatalog = runCatching { InstalledAppDirectory(app).listLaunchableApps() }.getOrDefault(emptyList())
            .filter { ACommandProtocol.validPackage(it.packageName) && commandText(it.label) }
            .distinctBy { it.packageName }.take(ACommandProtocol.MAX_APPS)
        val contacts = app.getSharedPreferences("saved_contacts", Context.MODE_PRIVATE).all.entries
            .filter { it.key.startsWith("contact:") && it.value is String && commandText(it.key.removePrefix("contact:")) }
            .sortedBy { it.key }.mapNotNull {
                ContactMatcher.normalizePhoneNumber(it.value as String)?.let { number -> CallChoice(it.key.removePrefix("contact:"), number) }
            }.take(ACommandProtocol.MAX_CONTACTS).mapIndexed { index, contact -> "c$index" to contact }.toMap()
        val wireContacts = contacts.map { ACommandContact(it.key, it.value.name) }
        val request = runCatching {
            ACommandProtocol.fitForProxy(ACommandRequest(task, utterance.trim(),
                appCatalog.map { ACommandApp(it.packageName, it.label) }, wireContacts, captured?.screen))
        }.getOrElse { result(task, "command_failed", it.message ?: "无法整理本次指令，请重新简短描述"); return }
        val sentAppIds = request.apps.map { it.id }.toSet()
        val apps = appCatalog.filter { it.packageName in sentAppIds }.associateBy { it.packageName }
        val client = runCatching {
            ACommandProtocol.validateRequest(request)
            ACommandApiClient(aCommandUrl, aCommandToken, aCommandLan, BuildConfig.DEBUG)
        }.getOrElse { result(task, "command_failed", it.message ?: "智能指令配置不可用"); return }
        commandClient?.cancelAll(); commandClient = client
        val ticket = client.ticket()
        voiceProgress("understanding", "正在请千问理解这句话，请稍候")
        update("status", "A 正在理解自然语言指令；B 已暂停")
        main.removeCallbacks(aTimeout); main.postDelayed(aTimeout, 25000)
        commandWorker.execute {
            val outcome = runCatching { client.interpret(request, ticket) }
            main.post {
                if (session.aTask != task || session.generation != generation || connectionEpoch != connection || commandClient !== client) return@post
                commandClient = null; client.cancelAll()
                outcome.onSuccess { response ->
                    when (val intent = response.intent) {
                        is ACommandIntent.Clarify -> clarifyCommand(task, utterance, intent.message)
                        is ACommandIntent.Unsupported -> result(task, "unsupported", intent.message)
                        is ACommandIntent.Vision -> startVisualQuestion(task, utterance, eventId, VisionMode.valueOf(intent.mode.name))
                        is ACommandIntent.CallContact -> {
                            val choice = contacts[intent.target]
                            if (choice == null) result(task, "command_failed", "联系人不在本次保存列表中，没有拨号")
                            else offerContactCall(task, text, listOf(choice))
                        }
                        else -> offerCommand(task, intent, request, apps, contacts, captured)
                    }
                }.onFailure { error ->
                    val message = when ((error as? ACommandHttpException)?.statusCode) {
                        401, 403 -> "智能指令服务拒绝访问，请检查自有代理访问令牌"
                        404 -> "云端尚未接入 A 智能指令接口，请让后端更新本次代理后重试"
                        429 -> "智能指令服务繁忙，请稍后重新按 A"
                        else -> "智能指令服务暂时不可用或返回内容不完整，本次没有执行。请检查网络和服务配置后重试。"
                    }
                    result(task, "command_failed", message)
                }
            }
        }
    }

    private fun clarifyCommand(task: String, utterance: String, question: String) {
        val confirmationId = "a-${UUID.randomUUID()}"
        val clarification = CommandClarification(utterance, question, now() + 45000, confirmationId)
        commandClarification = clarification
        val message = "$question 请在45秒内按 A 补充（需眼镜支持语音应答）；也可以重新说出完整指令，或点击取消。"
        voiceProgress("waiting_confirm", message); update("status", message)
        send((aRecording?.correlation() ?: JSONObject()).put("v", 2).put("type", "a_clarification")
            .put("task_id", task).put("prompt_id", confirmationId).put("question", message).put("source_button", "A"))
        if (ttsEnabled) speaker.speak(message)
        val generation = session.generation
        val cancel = { if (session.aTask == task && session.generation == generation && commandClarification === clarification)
            result(task, "command_cancelled", "已取消本次指令") }
        if (!AContactDialog.show("请补充指令", message, emptyList(), cancel, "取消指令") &&
            bController?.showAActions("请补充指令", message, listOf("取消指令" to cancel)) == true) AContactDialog.deliveredElsewhere()
        main.removeCallbacks(aTimeout); main.postDelayed(aTimeout, 45000)
    }

    private fun offerCommand(task: String, intent: ACommandIntent, request: ACommandRequest,
                             apps: Map<String, InstalledApp>, contacts: Map<String, CallChoice>, captured: ACommandScreenController.Captured?) {
        val label = when (intent) {
            is ACommandIntent.OpenApp -> "打开${apps[intent.target]?.label ?: "目标应用"}"
            is ACommandIntent.Navigate -> when (intent.target) { ACommandNavigation.BACK -> "返回上一页"; ACommandNavigation.HOME -> "返回手机桌面"; ACommandNavigation.RECENTS -> "显示最近使用的应用" }
            is ACommandIntent.ExitApp -> "退出${apps[intent.target]?.label ?: "当前应用"}并返回桌面"
            is ACommandIntent.ScreenAction -> {
                val target = request.screen?.controls?.singleOrNull { it.id == intent.target }
                if (captured == null || target == null || intent.action !in target.actions) {
                    result(task, "command_failed", "没有可以核对的当前页面控件，本次没有执行"); return
                }
                when (intent.action) { ACommandAction.CLICK -> "点击“${target.label}”"; ACommandAction.SCROLL_FORWARD -> "向下翻一屏"; ACommandAction.SCROLL_BACKWARD -> "向上翻一屏" }
            }
            else -> { result(task, "unsupported", "这条指令需要进一步说明"); return }
        }
        val expiry = now() + 30000
        val confirmationId = "a-${UUID.randomUUID()}"
        val offer = CommandOffer(intent, request, apps, contacts, captured, expiry,
            ACommandExecutionGate(expiry, if (intent is ACommandIntent.ScreenAction) captured?.snapshot?.uiFingerprint else null),
            confirmationId)
        commandOffer?.gate?.invalidate(); commandOffer = offer
        commandClarification = null
        val generation = session.generation
        fun current() = session.aTask == task && session.generation == generation && commandOffer === offer
        val confirm = { if (current()) { commandOffer = null; executeCommand(task, offer) } }
        val decline = { if (current()) result(task, "command_cancelled", "已取消，没有执行手机操作") }
        val message = "准备$label。确认后只执行这一步。请在30秒内点击下面的按钮确认或取消；支持语音应答的眼镜也可按 A 回答。"
        if (!AContactDialog.show("请确认手机操作", message, listOf("确认：$label" to confirm), decline, "取消操作") &&
            bController?.showAActions("请确认手机操作", message, listOf("确认：$label" to confirm, "取消操作" to decline)) == true) AContactDialog.deliveredElsewhere()
        voiceProgress("waiting_confirm", message); update("status", message)
        send((aRecording?.correlation() ?: JSONObject()).put("v", 2).put("type", "a_clarification")
            .put("task_id", task).put("prompt_id", confirmationId).put("question", message).put("source_button", "A"))
        if (ttsEnabled) speaker.speak(message)
        main.removeCallbacks(aTimeout); main.postDelayed(aTimeout, 30000)
    }

    private fun executeCommand(task: String, offer: CommandOffer) {
        AContactDialog.clear(); bController?.clearAContactPrompt(); stopSpeaking()
        val generation = session.generation
        voiceProgress("executing", "已确认，正在核对并执行这一步")
        // Let the confirmation overlay close before reading the target app's actual window.
        main.postDelayed({
            if (session.aTask != task || session.generation != generation) { offer.gate.invalidate(); return@postDelayed }
            if (app.getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
                offer.gate.invalidate(); result(task, "locked", "手机已锁屏，本次没有执行"); return@postDelayed
            }
            when (val intent = offer.intent) {
                is ACommandIntent.ScreenAction -> {
                    val service = accessibility
                    val captured = offer.captured
                    if (service == null || captured == null || !aScreenAllowed) {
                        offer.gate.invalidate(); result(task, "permission_denied", "页面操作需要开启无障碍和 A 页面信息授权，本次没有执行")
                        permissionIssue("请开启助手无障碍服务，并在 A 智能指令设置中允许读取本次页面控件。")
                    } else {
                        val controller = ACommandScreenController(service)
                        val outcome = controller.execute(captured, intent, offer.gate)
                        if (!outcome.first) result(task, "action_failed", outcome.second)
                        else main.postDelayed({
                            if (session.aTask == task && session.generation == generation)
                                result(task, "action_requested", controller.verifyChanged(captured))
                        }, 800)
                    }
                }
                is ACommandIntent.ExitApp -> {
                    val service = accessibility
                    if (service == null) {
                        offer.gate.invalidate(); result(task, "permission_denied", "无障碍服务不可用，本次没有退出应用")
                        permissionIssue("请开启助手无障碍服务，以便确认当前应用并执行退出。")
                        return@postDelayed
                    }
                    if (ACommandScreenController(service).currentPackage() != intent.target) {
                        offer.gate.invalidate(); result(task, "page_changed", "当前应用已经变化，旧退出指令已取消")
                    } else if (offer.gate.claim(now())) performNavigation(task, ACommandNavigation.HOME)
                    else result(task, "command_expired", "确认已过期或已处理，没有重复执行")
                }
                else -> {
                    if (!offer.gate.claim(now())) { result(task, "command_expired", "确认已过期或已处理，没有重复执行"); return@postDelayed }
                    when (intent) {
                        is ACommandIntent.OpenApp -> {
                            val selected = offer.apps[intent.target]
                            val directory = InstalledAppDirectory(app)
                            val fresh = runCatching { directory.listLaunchableApps().singleOrNull { it == selected } }.getOrNull()
                            val launchIntent = fresh?.let(directory::launchIntent)
                            if (launchIntent == null) result(task, "app_changed", "目标应用已变化或不可用，请重新发起")
                            else launch(task, launchIntent, "已请求打开${fresh.label}")
                        }
                        is ACommandIntent.Navigate -> performNavigation(task, intent.target)
                        else -> result(task, "unsupported", "当前操作不受支持，没有执行")
                    }
                }
            }
        }, 450)
    }

    private fun performNavigation(task: String, target: ACommandNavigation) {
        val service = accessibility
        if (service == null) {
            result(task, "permission_denied", "需要开启助手无障碍服务才能返回、退出或切换应用。请点击“一键修复权限”。")
            permissionIssue("开启助手无障碍服务后，即可通过语音返回、退出和切换应用。")
            return
        }
        val action = when (target) {
            ACommandNavigation.BACK -> AccessibilityService.GLOBAL_ACTION_BACK
            ACommandNavigation.HOME -> AccessibilityService.GLOBAL_ACTION_HOME
            ACommandNavigation.RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS
        }
        val accepted = runCatching { service.performGlobalAction(action) }.getOrDefault(false)
        result(task, if (accepted) "action_requested" else "action_failed", if (accepted) when (target) {
            ACommandNavigation.BACK -> "已请求返回上一页，本次结束"
            ACommandNavigation.HOME -> "已请求返回桌面，本次结束"
            ACommandNavigation.RECENTS -> "已请求显示最近使用的应用，本次结束"
        } else "系统未接受这一步操作，本次已停止")
    }

    private fun permissionIssue(reason: String) {
        PermissionRecovery.report(app, reason)
        update("permission_required", reason)
        if (!homeForeground) bController?.showPermissionRepair(reason)
    }
    private fun aVisionProgress(message: String) {
        voiceProgress("vision", message)
        update("status", message)
    }
    private fun dialSavedContact(task: String, choice: CallChoice) {
        if (session.aTask != task) return
        val saved = app.getSharedPreferences("saved_contacts", Context.MODE_PRIVATE).getString("contact:${choice.name}", null)
        when {
            saved == null || ContactMatcher.normalizePhoneNumber(saved) != choice.number ||
                ContactMatcher.normalizePhoneNumber(choice.number) != choice.number -> result(task, "contact_changed", "联系人号码已变化或无效，请重新保存后发起")
            app.getSystemService(KeyguardManager::class.java).isKeyguardLocked -> result(task, "locked", "请解锁手机后重新发起拨号")
            app.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED -> {
                result(task, "permission_denied", "电话权限尚未开启，请点击“一键修复权限”，完成后重新说出拨号指令")
                permissionIssue("开启电话权限后，即可拨打您保存的联系人。")
            }
            else -> launch(task, Intent(Intent.ACTION_CALL, Uri.fromParts("tel", choice.number, null)), "已请求拨打${choice.name}")
        }
    }
    private fun offerContactCall(task: String, heard: String, choices: List<CallChoice>) {
        if (choices.isEmpty() || choices.size > 5) { result(task, "contact_ambiguous", "同音联系人较多，请使用保存的完整姓名重新发起"); return }
        val confirmationId = "a-${UUID.randomUUID()}"
        val offer = CallOffer(choices, now() + 30_000, confirmationId)
        callOffer = offer
        val message = if (choices.size == 1) "听到的是“$heard”，找到已保存联系人“${choices[0].name}”。要拨打吗？请在30秒内点击下方按钮；支持语音应答的眼镜也可按 A 说“确认”或“取消”。"
            else "“$heard”对应多个联系人：" + choices.mapIndexed { i, c -> "第${i + 1}个${c.name}，尾号${c.number.takeLast(4)}" }.joinToString("；") + "。请在30秒内选择下方联系人；支持语音应答的眼镜也可按 A 说“第一个”“第二个”等。"
        val generation = session.generation
        fun current() = session.aTask == task && session.generation == generation && callOffer === offer
        fun choose(choice: CallChoice) {
            if (!current()) return
            callOffer = null; AContactDialog.clear(); bController?.clearAContactPrompt()
            if (now() > offer.expiresAt) result(task, "call_expired", "联系人确认已过期，请重新发起")
            else dialSavedContact(task, choice)
        }
        val actions = choices.map { choice -> "拨打${choice.name}（尾号${choice.number.takeLast(4)}）" to { choose(choice) } }
        val decline = { if (current()) result(task, "call_cancelled", "已取消拨号") }
        if (!AContactDialog.show("请确认拨号联系人", message, actions, decline) &&
            bController?.showAContactChoices(message, actions, decline) == true) AContactDialog.deliveredElsewhere()
        voiceProgress("waiting_confirm", message); update("status", message)
        send((aRecording?.correlation() ?: JSONObject()).put("v", 2).put("type", "a_clarification")
            .put("task_id", task).put("prompt_id", confirmationId).put("question", message).put("source_button", "A"))
        if (ttsEnabled) speaker.speak(message)
        main.removeCallbacks(aTimeout); main.postDelayed(aTimeout, 30_000)
    }
    private fun launch(task: String, intent: Intent, message: String) {
        if (!homeForeground && accessibility == null) {
            result(task, "background_unavailable", "后台操作需要开启助手无障碍服务。请点击通知中的“一键修复权限”。")
            permissionIssue("开启助手无障碍服务后，在其他应用中也能接收语音并打开目标应用。")
            return
        }
        runCatching { (accessibility ?: app).startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onSuccess { result(task, "intent_requested", message) }
            .onFailure {
                result(task, "launch_failed", "系统未接受本次操作，请点击“一键修复权限”检查设置后重试")
                permissionIssue("系统没有允许本次启动，请检查无障碍和后台运行设置。")
            }
    }
    private fun imageReady(event: DeviceEvent) {
        val task = event.data.optString("task_id"); val frame = event.data.optString("frame_id")
        visionBinding?.let { binding ->
            if (binding.optString("api_contract") == "medicine-v1") {
                require(session.aTask == task && binding.getString("a_task") == task && frame == "f0" &&
                    event.data.optString("vision_task_id") == binding.getString("task_id")) { "照片上报不属于当前交接服务任务" }
                val mode = VisionMode.valueOf(binding.getString("mode"))
                if (mode == VisionMode.OCR) { ack(event.id, "polling_ocr", "正在查询本次 OCR 上传和识别结果"); return }
                if (frameBusy) { ack(event.id, "duplicate_frame", "本次照片正在处理，不重复下载"); return }
                val location = event.data.optString("url").ifBlank { event.data.optString("image_url") }
                if (location.isBlank()) {
                    result(task, "vision_failed", "物体识别需要固件返回本次照片的可读取 URL；目前只有上传完成通知。可先在手机选 JPEG 测试服务器。"); return
                }
                frameBusy = true
                val generation = session.generation; val connection = connectionEpoch; val ticket = taskPhotos.ticket()
                val config = ServerConfig.parse(medicineUrl, medicineLan, BuildConfig.DEBUG)
                val sources = listOf(TaskPhotoDownloader.Source(config.base, medicineToken)) +
                    runCatching {
                        val legacy = java.net.URI(baseUrl)
                        require(legacy.scheme == "https" && !legacy.host.isNullOrBlank() && legacy.userInfo == null)
                        listOf(TaskPhotoDownloader.Source(legacy))
                    }.getOrDefault(emptyList())
                aVisionProgress("A 正在读取本次物体照片")
                network.execute {
                    val image = runCatching { taskPhotos.download(location, sources, ticket) }
                    main.post {
                        if (session.aTask != task || session.generation != generation || connectionEpoch != connection || visionBinding !== binding) return@post
                        image.mapCatching {
                            check(medicineVision.provideFrame(binding.getString("task_id"), it)) { "本次照片已过期或重复，未继续识别，请重新拍摄" }
                        }.onFailure { result(task, "vision_failed", it.message ?: "本次照片下载失败") }
                    }
                }
                return
            }
            require(session.aTask == task && binding.getString("a_task") == task && frame == "f0" &&
                event.data.optString("vision_task_id") == binding.getString("task_id")) { "照片上报不属于当前统一视觉任务" }
            ack(event.id, "polling_vision", "手机按本次任务编号轮询，以服务器终态为准")
            return
        }
        ocrBinding?.let { binding ->
            require(session.aTask == task && binding.getString("a_task") == task && frame == "f0" &&
                event.data.optString("vision_task_id") == binding.getString("task_id")) { "OCR 照片上报不属于当前任务，已忽略" }
            ack(event.id, "polling_ocr", "手机按原 OCR 编号查询；以服务终态为准")
            return
        }
        require(task.isNotBlank() && session.aTask == task && awaitingFrame == task && frame == "$task-frame-1" && !frameBusy) { "照片不属于当前 A 任务，已忽略" }
        val question = aQuestion ?: error("没有对应问题")
        val location = event.data.optString("url").ifBlank { event.data.optString("image_url") }
        require(location.isNotBlank()) { "image_ready 需要当前照片的 HTTPS url，不使用最近一张缓存图" }
        frameBusy = true; val connection = connectionEpoch; val generation = session.generation
        val requestTicket = aVision.ticket()
        aVisionProgress("A 照片已关联，正在分析")
        main.removeCallbacks(aTimeout); main.postDelayed(aTimeout, 65000)
        network.execute {
            val answer = runCatching {
                val jpeg = aVision.downloadFrame(baseUrl, location, requestTicket)
                VisionClient.answer(aVision.call(baseUrl, apiToken, jpeg, question, requestTicket))
            }
            main.post {
                if (session.aTask != task || connection != connectionEpoch || generation != session.generation) return@post
                answer.onSuccess { result(task, "vision_answer", it) }
                    .onFailure { result(task, "vision_failed", "照片读取或视觉分析失败；请检查图片地址、任务编号及后端能力") }
            }
        }
    }

    /** Called after the document picker; a new A during selection invalidates the old selection. */
    fun selectedOcrImage(uri: Uri, question: String, pickerGeneration: Long) {
        if (pickerGeneration != session.generation) { update("status", "选图期间任务已变化，请重新选择照片"); return }
        val task = "photo-${UUID.randomUUID()}"
        beginA(task)
        val generation = session.generation
        aVisionProgress("正在读取所选 JPEG")
        network.execute {
            val read = runCatching {
                require(uri.scheme == "content") { "请从系统文件选择器选择 JPEG" }
                val bytes = app.contentResolver.openInputStream(uri)?.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= 8 * 1024 * 1024) { "JPEG 不能超过 8 MiB" }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                } ?: error("无法读取所选文件")
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                require(bounds.outMimeType == "image/jpeg" && bounds.outWidth > 0 && bounds.outHeight > 0 &&
                    bounds.outWidth.toLong() * bounds.outHeight <= 16_000_000) { "需要有效 JPEG，最多 1600 万像素" }
                bytes
            }
            main.post {
                if (session.aTask != task || session.generation != generation) return@post
                read.onSuccess {
                    when {
                        medicineEnabled -> startMedicineVision(task, question, it)
                        unifiedVisionEnabled -> startUnifiedVision(task, question, it)
                        else -> startOcr(task, question, it)
                    }
                }
                    .onFailure { result(task, "image_invalid", it.message ?: "读取 JPEG 失败") }
            }
        }
    }

    fun resumeLastOcr() {
        if (medicineEnabled) {
            val previous = runCatching { JSONObject(prefs.getString("last_medicine_ocr", "").orEmpty()) }.getOrNull()
            if (previous == null) { update("status", "还没有可查询的交接服务 OCR 任务"); return }
            if (previous.optString("server") != medicineUrl || previous.optString("mode") != VisionMode.OCR.name) {
                update("status", "上次任务不属于当前服务器或不是单独 OCR，请重新发起"); return
            }
            val task = "resume-${UUID.randomUUID()}"
            beginA(task); callOffer = null
            startMedicineVision(task, previous.getString("question"), resume = previous)
            return
        }
        val previous = runCatching { JSONObject(prefs.getString("last_ocr", "").orEmpty()) }.getOrNull()
        if (previous == null) { update("status", "还没有可查询的 OCR 任务"); return }
        val task = "resume-${UUID.randomUUID()}"
        beginA(task)
        startOcr(task, previous.optString("question"), resume = previous)
    }

    private fun startOcr(task: String, text: String, jpeg: ByteArray? = null, resume: JSONObject? = null) {
        if (session.aTask != task) return
        runCatching {
            require(ocrUrl.isNotBlank()) { "请先在 A/B 设置中配置独立 OCR 服务地址和令牌" }
            val base = OcrProtocol.endpoint(ocrUrl, ocrLan, BuildConfig.DEBUG)
            val token = ocrToken
            require(token.isNotBlank()) { "请填写 OCR 服务的 Bearer 令牌" }
            val question = OcrProtocol.normalizeQuestion(text)
            require(question.length in 1..1000 && OcrProtocol.supports(question)) { "请输入读文字或药盒字段问题" }
            val id = resume?.getString("task_id") ?: OcrProtocol.newId()
            OcrProtocol.checkId(id)
            if (resume != null) require(resume.getString("base") == base.toString()) { "上次任务属于另一 OCR 服务，请恢复原服务配置后查询" }
            val sha = jpeg?.let { OcrApiClient.sha256(it) } ?: resume?.optString("sha256")?.takeIf { it.isNotBlank() }
            val binding = JSONObject().put("task_id", id).put("a_task", task).put("question", question)
                .put("base", base.toString()).put("sha256", sha ?: "")
            // Save before the first remote write so a lost response can be queried by the same ID.
            check(prefs.edit().putString("last_ocr", binding.toString()).commit()) { "无法保存任务编号，本次未提交" }
            ocrBinding = binding
            val generation = session.generation; val connection = connectionEpoch
            fun current() = session.aTask == task && session.generation == generation &&
                connectionEpoch == connection && ocrBinding === binding
            main.removeCallbacks(aTimeout); main.postDelayed(aTimeout, 285000)
            aVisionProgress(if (resume == null) "A 正在连接 OCR 服务" else "A 正在查询上次 OCR 任务")
            records.record("ocr_requested", aEventId ?: task, "button_A", text, "requested", JSONObject(binding.toString()))
            ocr.start(base, token, id, question, jpeg, sha, resume != null, created = {
                if (current()) {
                    send(JSONObject().put("v", 2).put("type", "capture").put("source_button", "A")
                        .put("task_id", task).put("vision_task_id", id).put("frame_id", "f0").put("question", question)
                        .put("upload_url", base.resolve("api/v1/vision/tasks/$id/frames/f0").toString()).put("content_type", "image/jpeg"))
                    aVisionProgress("A 已建 OCR 任务，等待眼镜上传本次照片")
                }
            }, progress = { if (current()) aVisionProgress("A OCR：$it") }, finished = { outcome ->
                if (current()) outcome.onSuccess { answer ->
                    result(task, "ocr_${answer.type}", answer.display(), JSONObject(binding.toString())
                        .put("answer_type", answer.type).put("answer", answer.answer)
                        .put("evidence_text", answer.evidence).put("sha256", answer.sha256))
                }.onFailure { error ->
                    result(task, "ocr_failed", "${error.message ?: "OCR 请求失败"}\n可在设置中按原编号查询上次 OCR 任务。", JSONObject(binding.toString()))
                }
            })
        }.onFailure { result(task, "ocr_unavailable", it.message ?: "OCR 未能启动") }
    }

    private fun startUnifiedVision(task: String, question: String, jpeg: ByteArray? = null, forcedMode: VisionMode? = null) {
        if (session.aTask != task) return
        runCatching {
            val config = ServerConfig.parse(unifiedVisionUrl, unifiedVisionLan, BuildConfig.DEBUG)
            val id = OcrProtocol.newId()
            val mode = forcedMode ?: VisionApiClient.mode(question)
            val binding = JSONObject().put("task_id", id).put("vision_task_id", id).put("a_task", task).put("mode", mode.name)
            visionBinding = binding
            val generation = session.generation; val connection = connectionEpoch
            fun current() = session.aTask == task && session.generation == generation && connectionEpoch == connection && visionBinding === binding
            main.removeCallbacks(aTimeout); main.postDelayed(aTimeout, 155000)
            aVisionProgress("A 正在创建 $mode 视觉任务")
            val recordBinding = aRecording?.correlation() ?: JSONObject().put("task_id", task)
            recordBinding.put("vision_task_id", id).put("mode", mode.name)
            records.record("vision_requested", aEventId ?: task, "button_A", question, "requested", recordBinding)
            unifiedVision.start(config, unifiedVisionToken, id, question, mode, jpeg, created = created@ { upload ->
                if (current()) {
                    val capture = (aRecording?.correlation() ?: JSONObject()).put("v", 2).put("type", "capture").put("source_button", "A")
                        .put("task_id", task).put("vision_task_id", id).put("event_id", aEventId ?: task)
                        .put("frame_id", upload.frameId).put("mode", mode.name).put("question", question)
                        .put("upload_url", upload.url).put("upload_method", "PUT").put("upload_headers", upload.headers)
                        .put("upload_expires_at", upload.expiresAt.toString()).put("content_type", "image/jpeg")
                    aRecording?.let { capture.put("session_id", it.sessionId) }
                    if (!send(capture)) {
                        result(task, "vision_failed", "拍照消息未能进入蓝牙发送队列，请确认双向连接和上传凭证大小后重试")
                        return@created
                    }
                    IntegrationLog.event("VISION", "capture_sent", mapOf("task_id" to id, "mode" to mode.name))
                    aVisionProgress("A 等待眼镜 Wi-Fi 直传本次照片：$question")
                }
            }, progress = { if (current()) aVisionProgress("A $mode：$it") }, finished = { outcome ->
                if (current()) outcome.onSuccess { answer ->
                    result(task, "vision_answer", answer.display(), JSONObject(binding.toString()).put("sha256", answer.sha256))
                }.onFailure { result(task, "vision_failed", it.message ?: "统一视觉任务失败，请检查后端接口") }
            })
        }.onFailure { result(task, "vision_failed", it.message ?: "统一视觉配置不可用") }
    }

    private fun startMedicineVision(task: String, question: String, jpeg: ByteArray? = null, resume: JSONObject? = null, forcedMode: VisionMode? = null) {
        if (session.aTask != task) return
        callOffer = null; AContactDialog.clear(); bController?.clearAContactPrompt()
        runCatching {
            val config = ServerConfig.parse(medicineUrl, medicineLan, BuildConfig.DEBUG)
            val token = medicineToken
            val id = resume?.getString("task_id") ?: OcrProtocol.newId()
            val mode = if (resume == null) forcedMode ?: VisionApiClient.mode(question) else VisionMode.OCR
            val eventId = aEventId ?: task
            val recordId = if (resume == null) eventId else resume.optString("record_id").takeIf { it.isNotBlank() }
            val binding = JSONObject().put("api_contract", "medicine-v1").put("server", medicineUrl)
                .put("task_id", id).put("vision_task_id", id).put("a_task", task).put("mode", mode.name).put("question", question)
            if (recordId != null) binding.put("record_id", recordId)
            if (mode == VisionMode.OCR) check(prefs.edit().putString("last_medicine_ocr", binding.toString()).commit()) { "任务编号保存失败，未提交" }
            visionBinding = binding
            val generation = session.generation; val connection = connectionEpoch
            fun current() = session.aTask == task && session.generation == generation && connectionEpoch == connection && visionBinding === binding
            aVisionProgress(if (resume == null) "A 正在检查交接服务：$mode" else "A 正在按原编号查询交接服务 OCR")
            main.removeCallbacks(aTimeout); main.postDelayed(aTimeout, 185_000)
            records.record("vision_requested", eventId, "button_A", question, "requested", JSONObject(binding.toString()))
            medicineVision.start(config, token, id, question, mode, jpeg, recordId, resume != null,
                created = { capture ->
                    if (current()) {
                        val message = (aRecording?.correlation() ?: JSONObject()).put("v", 2).put("type", "capture")
                            .put("source_button", "A").put("task_id", task).put("vision_task_id", id)
                            .put("event_id", eventId).put("frame_id", capture.frameId).put("mode", mode.name).put("question", question)
                        if (capture.uploadUrl != null) message.put("upload_url", capture.uploadUrl)
                            .put("upload_method", "PUT").put("upload_headers", capture.headers).put("upload_owner", "esp32")
                        if (mode != VisionMode.OCR) message.put("return_image_url", true)
                        if (!send(message)) result(task, "vision_failed", "拍照指令未发送，请先连接可双向收发的眼镜")
                        else aVisionProgress(if (mode == VisionMode.OCR) "A 等待眼镜按本次任务上传 JPEG" else "A 等待眼镜返回本次物体照片 URL")
                    }
                }, progress = { if (current()) aVisionProgress("A $mode：$it") }, finished = { outcome ->
                    if (current()) outcome.onSuccess { answer ->
                        result(task, "vision_answer", answer.display(), JSONObject(binding.toString())
                            .put("evidence_text", answer.evidence).put("review_required", answer.reviewRequired))
                    }.onFailure { result(task, "vision_failed", it.message ?: "交接服务识别失败") }
                })
        }.onFailure { result(task, "vision_failed", it.message ?: "交接服务配置错误") }
    }

    private fun result(task: String, status: String, message: String, data: JSONObject = JSONObject()) {
        if (session.aTask != task) return
        commandClient?.cancelAll(); commandClient = null
        commandOffer?.gate?.invalidate(); commandOffer = null; commandClarification = null
        aVision.cancelAll(); awaitingFrame = null; aQuestion = null; frameBusy = false
        ocr.cancel(); ocrBinding = null
        unifiedVision.cancel(); medicineVision.cancel(); taskPhotos.cancel(); visionBinding = null
        callOffer = null; AContactDialog.clear(); bController?.clearAContactPrompt()
        main.removeCallbacks(aTimeout); main.removeCallbacks(transferTimeout)
        recognitionEpoch++; speech.cancel(); isRecognizing = false; recognizingButton = null
        pending = null; framed = null; transfer.abort()
        send((aRecording?.correlation() ?: JSONObject()).put("v", 2).put("type", "result").put("event_id", aEventId ?: task)
            .put("task_id", task).put("status", status).put("reply", message.take(1800)))
        aRecording?.let {
            data.put("session_id", it.sessionId).put("event_id", it.eventId).put("task_id", it.taskId)
                .put("prompt_id", it.promptId ?: JSONObject.NULL).put("source_button", it.button)
        }
        records.record("action_result", aEventId ?: task, "button_A", result = status, data = data.put("reply", message))
        aRecording = null
        session.finishA(task, now())
        aEventId = null
        val success = status == "intent_requested" || status == "action_requested" || status == "vision_answer" ||
            (status.startsWith("ocr_") && status !in setOf("ocr_failed", "ocr_unavailable"))
        voiceProgress(if (success || status in setOf("call_cancelled", "command_cancelled")) "done" else "error", message)
        update("a_result", message)
        IntegrationLog.event("A", "result", mapOf("task_id" to task, "state" to status), elapsedMs = (now() - aStartedAt).coerceAtLeast(0))
        if (ttsEnabled) speaker.speak(message)
        if (status.startsWith("ocr_") || status.startsWith("vision_")) {
            val title = if (status in setOf("ocr_failed", "ocr_unavailable", "vision_failed")) "视觉识别未完成" else "文字与物体识别结果"
            if (!OcrResultDialog.show(title, message) && bController?.showAResult(message, title, persistent = true) == true)
                OcrResultDialog.deliveredElsewhere()
        } else if (!homeForeground) bController?.showAResult(message)
    }
    fun recordBResult(message: String, text: String, envelope: RecordingEnvelope? = null) {
        IntegrationLog.event("B", "result")
        records.record("assist_result", envelope?.eventId ?: "b-${UUID.randomUUID()}", "button_B", text, "advice",
            (envelope?.correlation() ?: JSONObject()).put("reply", message))
    }
    fun cancelAll(reason: String) {
        if (!initialized) return
        commandClient?.cancelAll(); commandClient = null
        commandOffer?.gate?.invalidate(); commandOffer = null; commandClarification = null
        stopSpeaking()
        IntegrationLog.event("runtime", "cancelled")
        OcrResultDialog.clear()
        callOffer = null; AContactDialog.clear(); bController?.clearAContactPrompt()
        bController?.cancel(reason) ?: session.clearB(now())
        session.aTask?.let { session.finishA(it, now()) }
        recognitionEpoch++; speech.cancel(); isRecognizing = false; recognizingButton = null
        aVision.cancelAll(); awaitingFrame = null; aQuestion = null; frameBusy = false
        ocr.cancel(); ocrBinding = null
        unifiedVision.cancel(); medicineVision.cancel(); taskPhotos.cancel(); visionBinding = null; aRecording = null
        pending = null; raw = null; framed = null; transfer.abort()
        main.removeCallbacks(aTimeout); main.removeCallbacks(transferTimeout)
        NextActionRuntime.cancel(reason)
        voiceProgress("idle", reason)
        update("status", reason)
    }
    private fun disconnected() {
        IntegrationLog.event("BLE", "disconnected")
        connectionEpoch++; metadataRequired = false; rejectNextRaw = false; firmwareSession = null
        textFrames.reset()
        textConnectionId = UUID.randomUUID().toString()
        cancelAll("蓝牙已断开，旧 A/B 任务已清理")
        voiceProgress("idle", "等待硬件重新连接后发送录音")
        if (::hardwareConnection.isInitialized) hardwareConnection.disconnected()
    }
    fun stop() {
        sessionConsent = false
        hardwareConnection.pause()
        cancelAll("后台连接已停止")
    }
}
