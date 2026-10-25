package cn.seniorvoice.demo

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.view.View
import android.widget.*
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.UUID
import cn.seniorvoice.demo.nextaction.NextActionRuntime
import cn.seniorvoice.demo.nextaction.diagnostics.AgentSettingsActivity
import cn.seniorvoice.demo.nextaction.domain.TriggerSource
import cn.seniorvoice.demo.appcontrol.InstalledAppDirectory
import cn.seniorvoice.demo.setup.PermissionRequirements
import cn.seniorvoice.demo.setup.PermissionRecovery
import cn.seniorvoice.demo.setup.PermissionSetupActivity
import cn.seniorvoice.demo.assist.AssistRuntime
import cn.seniorvoice.demo.assist.AssistUpdate
import cn.seniorvoice.demo.assist.AssistSettingsActivity
import cn.seniorvoice.demo.assist.OcrResultDialog
import cn.seniorvoice.demo.assist.AContactDialog
import cn.seniorvoice.demo.assist.IntegrationDiagnosticsActivity

class HomeActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("app_settings", MODE_PRIVATE) }
    private val contacts by lazy { getSharedPreferences("saved_contacts", MODE_PRIVATE) }
    private val appDirectory by lazy { InstalledAppDirectory(this) }
    private var installedAppCount = -1
    private var appCatalogStatus = "正在读取手机中的可启动应用"
    private val main = Handler(Looper.getMainLooper())
    private val ble: BleClient get() = AssistRuntime.ble
    private lateinit var backend: BackendSync
    private lateinit var status: TextView
    private lateinit var bleStatus: TextView
    private lateinit var syncStatus: TextView
    private lateinit var analysisStatus: TextView
    private lateinit var permissionStatus: TextView
    private lateinit var heard: TextView
    private lateinit var logView: TextView
    private lateinit var contactList: LinearLayout
    private lateinit var deviceList: LinearLayout
    private lateinit var phoneMic: CheckBox
    private lateinit var serviceInput: EditText
    private lateinit var notifyInput: EditText
    private lateinit var writeInput: EditText
    private lateinit var developerScreen: View
    private lateinit var dashboardWebView: WebView
    private val discovered = mutableSetOf<String>()
    private val dashboardDevices = linkedMapOf<String, Pair<BluetoothDevice, String>>()
    private val logs = ArrayDeque<String>()
    private val dashboardRecords = mutableListOf<JSONObject>()
    private var active = false
    private var disposed = false
    private var dashboardLoaded = false
    private var dashboardVisible = false
    private var externalAction = false
    private var handling = false
    private var listening = false
    private var voiceErrors = 0
    private var recognizer: SpeechRecognizer? = null
    private var audioAnalysisInFlight = false
    private var lastRecording: File? = null
    private var player: MediaPlayer? = null
    private var bleSession = 0
    private var foregroundGeneration = 0
    private var backgroundStartAfterSetup = false
    private val assistObserver: (AssistUpdate) -> Unit = { value ->
        if (!disposed) {
            when (value.kind) {
                "device" -> value.device?.let { showDevice(it, value.message) }
                "ble" -> bleStatus.text = value.message
                "sync" -> syncStatus.text = value.message
                "records" -> refreshDashboardRecordsFromStore()
                "transcript" -> { heard.text = value.message; analysisStatus.text = "离线转写已完成，录音未上传" }
                "hardware_transcript" -> { heard.text = value.message; analysisStatus.text = "已收到 A 硬件识别文字（v1/en），跳过手机语音识别" }
                "audio" -> analysisStatus.text = value.message
                "voice_progress" -> analysisStatus.text = value.message
                "permission_required" -> {
                    status.text = value.message
                    main.post {
                        if (active && !disposed) PermissionRecovery.show(this, value.message)
                    }
                }
                "status", "a_result", "b_result" -> status.text = value.message
                // Synchronization, model preparation and B observation are separate states;
                // they must not overwrite the user's current command or result.
                else -> Unit
            }
            audioAnalysisInFlight = AssistRuntime.isRecognizing
            lastRecording = File(filesDir, "last-recording/recording.wav").takeIf { it.isFile }
            if (value.kind in setOf("a_result", "b_result")) {
                refreshDashboardRecordsFromStore()
                feedback(value.message)
            }
            publishDashboardState()
        }
    }
    private data class Action(val id: String, val source: String, val text: String)

    private val listenAgain = Runnable { startListening() }
    private val periodicSync = object : Runnable {
        override fun run() {
            if (!active || disposed) return
            backend.retry()
            main.postDelayed(this, 15000)
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        backgroundStartAfterSetup = state?.getBoolean("background_start_after_setup") == true
        NextActionRuntime.initialize(this)
        AssistRuntime.initialize(this)
        if (!prefs.contains("device_id")) prefs.edit().putString("device_id", "elder-${UUID.randomUUID().toString().take(8)}").apply()
        buildScreen()
        val recordings = File(filesDir, "last-recording").apply { mkdirs() }
        lastRecording = File(recordings, "recording.wav").takeIf { it.isFile }
        backend = BackendSync(
            context = this,
            settings = ::backendSettings,
            onStatus = { message -> main.post {
                if (!disposed) { syncStatus.text = message; publishDashboardState() }
            } },
            onChanged = { main.post { refreshDashboardRecordsFromStore() } }
        )
        refreshDashboardRecordsFromStore()
        AssistRuntime.addListener(assistObserver)
        refreshContacts()
        buildDashboard()
        showDashboard()
        if (state == null && PermissionRequirements.shouldOfferSetup(this)) openPermissionSetup()
    }

    private fun buildScreen() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; fitsSystemWindows = true
            setPadding(dp(20), dp(20), dp(20), dp(32)); setBackgroundColor(0xFFF5F7F7.toInt())
        }
        fun heading(text: String) { page.addView(label(text, 21f).apply { setPadding(0, dp(22), 0, dp(6)) }) }
        page.addView(label("语音生活助手", 28f))
        page.addView(button("返回整合首页") { showDashboard() })
        page.addView(label("收到语音指令自动执行，无需点击功能按钮", 15f))
        page.addView(label("结果显示在屏幕；可在 A/B 设置中启用手机中文朗读。", 14f))
        status = label("等待指令", 23f).apply { setPadding(0, dp(20), 0, dp(12)) }
        heard = label("尚未收到指令", 17f)
        page.addView(status); page.addView(heard)
        analysisStatus = label("等待 A 文字帧；也兼容原有硬件 WAV 录音", 16f)
        analysisStatus.setTextIsSelectable(true)
        page.addView(analysisStatus)
        page.addView(label("A：硬件识别 → BLE v1 文字帧 → 手机校验去重 → 执行明确指令。也兼容 WAV → 手机离线转文字。B 回答仍使用原有带元信息的录音路径。", 14f))
        page.addView(label("提前允许蓝牙和电话权限后，日常指令不再弹 App 确认框。电话会实际拨出；双卡选择等系统提示请在系统中设置默认通话卡。", 14f))
        page.addView(button("查看 / 复制本次联调报告") { showReport() })

        heading("AI 单步补全（新增，可选）")
        page.addView(button("A/B 眼镜帮助 · 配置与状态") { startActivity(Intent(this, AssistSettingsActivity::class.java)) })
        page.addView(label("预测当前页面的一个安全下一步，然后停止。先进入设置启用并了解屏幕采集；模拟练习不上传。原有语音指令无需此权限。", 14f))
        page.addView(button("AI 补全设置 / 调试 / 安全练习") { startActivity(Intent(this, AgentSettingsActivity::class.java)) })

        heading("手机操作检查（调试用）")
        page.addView(label("A 负责明确指令与实物问答；B 观察页面后先询问。开启后台连接与无障碍服务后，可切到目标 App 联调。", 14f))
        permissionStatus = label("正在检查权限", 15f); page.addView(permissionStatus)
        page.addView(button("首次使用 / 集中权限设置") { openPermissionSetup() })
        page.addView(button("查看手机中可打开的应用名称") { showInstalledApps() })
        page.addView(button("申请电话权限（只授权，不拨号）") {
            if (checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                refreshPhoneStatus(); toast("电话权限已经开启")
            } else {
                requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), CALL_SETUP_PERMISSION)
            }
        })
        page.addView(button("打开本 App 系统权限设置") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })
        page.addView(button("测试打开抖音") { debugCommand("打开抖音") })
        page.addView(label("下方测试同样直接执行，测试拨号会实际呼出，不再二次确认。", 14f))
        val testCommand = input("输入：打开 QQ / 启动微信 / 给女儿打电话", "")
        page.addView(testCommand)
        page.addView(button("测试这条文字指令") { debugCommand(testCommand.text.toString()) })
        heading("联系人设置")
        page.addView(label("保存在本 App 内，不读取手机通讯录。同一称呼保存一次。", 14f))
        val name = input("称呼，例如：女儿", "")
        val number = input("电话号码", "").apply { inputType = InputType.TYPE_CLASS_PHONE }
        page.addView(name); page.addView(number)
        page.addView(button("保存联系人") {
            val alias = name.text.toString().trim().replace(Regex("\\s+"), "")
            val phone = number.text.toString().trim().replace(Regex("[\\s()（）-]"), "")
            if (!Regex("[\\p{L}\\p{N}]{1,20}").matches(alias) || !Regex("\\+?[0-9]{7,15}").matches(phone)) {
                toast("称呼请填 1～20 个汉字/字母/数字，电话请填 7～15 位数字"); return@button
            }
            val save = {
                contacts.edit().putString("contact:$alias", phone).apply()
                name.text.clear(); number.text.clear(); refreshContacts(); feedback("已保存${alias}的电话")
            }
            if (contacts.contains("contact:$alias")) AlertDialog.Builder(this).setTitle("替换${alias}的号码？")
                .setPositiveButton("替换") { _, _ -> save() }.setNegativeButton("取消", null).show()
            else save()
        })
        contactList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(contactList)

        heading("设备连接设置")
        bleStatus = label("未连接硬件", 16f); page.addView(bleStatus)
        page.addView(label("首次连接时填写硬件同学提供的 UUID；默认值为 Nordic UART 服务。", 14f))
        page.addView(label("服务 Service UUID", 14f))
        serviceInput = input("Service UUID", prefs.getString("ble_service", BleClient.DEFAULT_SERVICE).orEmpty())
        page.addView(serviceInput)
        page.addView(label("接收 Notify UUID", 14f))
        notifyInput = input("Notify UUID", prefs.getString("ble_notify", BleClient.DEFAULT_NOTIFY).orEmpty())
        page.addView(notifyInput)
        page.addView(label("回传 Write UUID（需要支持 WRITE）", 14f))
        writeInput = input("Write UUID", prefs.getString("ble_write", BleClient.DEFAULT_WRITE).orEmpty())
        page.addView(writeInput)
        page.addView(button("搜索附近 BLE 设备") { requestBleScan() })
        page.addView(button("暂停硬件连接") { AssistRuntime.pauseHardware(); publishDashboardState() })
        page.addView(button("请求重发最近一条文字结果") { AssistRuntime.requestTextResend() })
        page.addView(label("文字 v1：A5 5A 帧头，自动拼接 BLE 分包并校验 CRC；同一连接内按序号去重。固件当前语言为 en，可测试 open QQ。", 14f))
        deviceList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; page.addView(deviceList)

        heading("后端记录存储（可选，不参与识别）")
        syncStatus = label("文字和执行结果先存本机；配置记录接口后后台同步", 15f); page.addView(syncStatus)
        page.addView(label("识别、匹配和手机操作均在本机。此处只发 JSON 记录，不上传 WAV。朋友需提供记录存储接口；图片 /analyze 不能用于保存这些记录。留空也能识别和操作手机。", 14f))
        page.addView(label("记录存储接口完整地址", 14f))
        val endpoint = input("后端如果提供事件接口，在此填完整地址", prefs.getString("endpoint", "").orEmpty())
        page.addView(endpoint)
        page.addView(label("老人设备编号（与后端约定一致）", 14f))
        val deviceId = input("设备编号", prefs.getString("device_id", "").orEmpty()); page.addView(deviceId)
        page.addView(label("接口令牌 Token（后端未设置时留空）", 14f))
        val token = input("可留空", prefs.getString("token", "").orEmpty()).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        page.addView(token)
        page.addView(button("保存后端设置并同步记录") {
            val url = endpoint.text.toString().trim()
            val valid = url.isEmpty() || runCatching {
                val uri = URI(url)
                uri.scheme in setOf("http", "https") && !uri.host.isNullOrEmpty() && uri.userInfo == null && uri.fragment == null
            }.getOrDefault(false)
            if (!valid || deviceId.text.toString().trim().isBlank() || token.text.contains('\n') || token.text.contains('\r')) {
                toast("请检查完整接口地址、设备编号和令牌"); return@button
            }
            prefs.edit().putString("endpoint", url).putString("token", token.text.toString().trim())
                .putString("device_id", deviceId.text.toString().trim()).apply()
            backend.retry(); toast("已保存；待上传的记录会继续重试")
        })
        heading("录音检查（调试用）")
        page.addView(button("试听上一段录音（核对是否有人声）") { playLastRecording() })
        page.addView(label("手动重新识别会再次执行录音中的指令，可能再次拨号。正常收到 WAV 时自动处理，不需点击。", 14f))
        page.addView(button("重新识别并执行上一段（直接执行）") {
            val file = lastRecording
            if (file?.isFile != true) { toast("还没有收到录音"); return@button }
            if (audioAnalysisInFlight) { toast("请等待当前接收或请求结束"); return@button }
            AssistRuntime.retryA(file)
        })

        heading("调试设置")
        page.addView(label("版本 ${BuildConfig.VERSION_NAME} · A 支持硬件文字帧 v1 / 原有 WAV、同音联系人确认、视觉服务器；A 优先，B Agent 单步确认；录音最大 10 MiB / 180 秒", 14f))
        page.addView(button("测试手机震动") { feedback("震动测试：正常结果短震一次；失败短震两次") })
        phoneMic = CheckBox(this).apply {
            text = "临时使用手机麦克风识别指令（默认关闭）"
            setOnCheckedChangeListener { _, checked ->
                if (checked && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC_PERMISSION)
                } else if (checked) { voiceErrors = 0; scheduleListening() } else stopListening()
            }
        }
        page.addView(phoneMic)
        page.addView(label("A 可直接接收硬件识别文字，无需手机麦克风权限；原有 WAV 仍由手机离线识别。此开关是额外的手机麦克风测试，依赖手机系统识别服务，可能联网；默认关闭。", 14f))
        page.addView(button("查看离线语音开源许可") {
            val notices = assets.open("licenses/THIRD_PARTY_NOTICES.txt").bufferedReader().use { it.readText() }
            val license = assets.open("licenses/APACHE-2.0.txt").bufferedReader().use { it.readText() }
            showDetails("离线语音开源许可", "$notices\n\n$license")
        })
        logView = label("联调日志会显示在这里", 13f); page.addView(logView)
        developerScreen = ScrollView(this).apply { isFillViewport = true; addView(page) }
    }

    private fun refreshContacts() {
        contactList.removeAllViews()
        val saved = contacts.all.filterKeys { it.startsWith("contact:") }.toSortedMap()
        if (saved.isEmpty()) contactList.addView(label("暂无联系人，请先保存一个测试联系人", 15f))
        saved.forEach { (key, value) ->
            val name = key.removePrefix("contact:")
            contactList.addView(label("$name　$value", 17f))
            contactList.addView(button("删除$name") {
                AlertDialog.Builder(this).setTitle("删除$name？").setPositiveButton("删除") { _, _ ->
                    contacts.edit().remove(key).apply(); refreshContacts()
                }.setNegativeButton("取消", null).show()
            })
        }
    }

    private fun buildDashboard() {
        val webView = WebView(this).apply {
            setBackgroundColor(0xFFEDF2EF.toInt())
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowContentAccess = false
                allowFileAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                setSupportZoom(false)
            }
            addJavascriptInterface(DashboardBridge(), "AndroidApp")
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    blockDashboardNavigation(request.url.toString())

                @Suppress("DEPRECATION")
                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                    blockDashboardNavigation(url)

                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    dashboardLoaded = true
                    publishDashboardState()
                }
            }
        }
        dashboardWebView = webView
        val html = assets.open("voice-life-demo.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
        webView.loadDataWithBaseURL(DASHBOARD_ORIGIN, html, "text/html", "UTF-8", null)
    }

    private fun blockDashboardNavigation(url: String): Boolean {
        val allowed = url.startsWith(DASHBOARD_ORIGIN)
        if (!allowed) main.post { toast("测试页面不会打开外部链接") }
        return !allowed
    }

    private fun showDashboard() {
        if (!::dashboardWebView.isInitialized) return
        dashboardVisible = true
        setContentView(dashboardWebView)
        dashboardWebView.onResume()
        publishDashboardState()
    }

    private fun showDeveloperSettings() {
        if (!::developerScreen.isInitialized) return
        dashboardVisible = false
        dashboardWebView.onPause()
        setContentView(developerScreen)
        if (::contactList.isInitialized) refreshContacts()
        if (::permissionStatus.isInitialized) refreshPhoneStatus()
    }

    private inner class DashboardBridge {
        @JavascriptInterface
        fun postMessage(raw: String) {
            if (raw.length > 4096 || disposed) return
            val request = runCatching { JSONObject(raw) }.getOrNull() ?: return
            val requestId = request.optString("request_id").take(80)
            when (request.optString("type")) {
                "get_state" -> main.post { sendDashboardMessage(dashboardState(requestId)) }
                "save_contact" -> main.post { saveDashboardContact(request, requestId) }
                "open_debug" -> main.post {
                    sendDashboardMessage(JSONObject().put("type", "result").put("request_id", requestId).put("ok", true))
                    showDeveloperSettings()
                }
                "open_setup" -> main.post { if (active && !disposed) openPermissionSetup() }
                "repair_permissions" -> main.post { if (active && !disposed) repairPermissions() }
                "open_assist" -> main.post {
                    if (active && !disposed) startActivity(Intent(this@HomeActivity, AssistSettingsActivity::class.java))
                }
                "scanHardware", "connectHardware", "disconnectHardware", "startBackgroundHardware",
                "stopBackgroundHardware", "setAutoReconnect", "openBackgroundSettings", "openDiagnostics" -> main.post {
                    if (active && !disposed) hardwareDashboardAction(request, requestId)
                }
            }
        }
    }

    private fun hardwareDashboardAction(request: JSONObject, requestId: String) {
        val type = request.optString("type")
        val ok = when (type) {
            "scanHardware" -> { requestBleScan(); true }
            "connectHardware" -> {
                val device = dashboardDevices[request.optString("address")]?.first
                if (device == null) false else connectHardware(device)
            }
            "disconnectHardware", "stopBackgroundHardware" -> { AssistRuntime.pauseHardware(); true }
            "startBackgroundHardware" -> startBackgroundHardware()
            "setAutoReconnect" -> { AssistRuntime.hardwareConnection.setAutoReconnect(request.optBoolean("enabled")); true }
            "openBackgroundSettings" -> { openBackgroundSettings(); true }
            "openDiagnostics" -> { startActivity(Intent(this, IntegrationDiagnosticsActivity::class.java)); true }
            else -> false
        }
        sendDashboardMessage(JSONObject().put("type", "result").put("request_id", requestId).put("ok", ok)
            .put("message", if (ok) "" else "未能完成，请检查蓝牙权限或重新搜索设备"))
        publishDashboardState()
    }

    private fun startBackgroundHardware(): Boolean {
        if (!hardwarePermissionsReady()) {
            backgroundStartAfterSetup = true
            repairPermissions()
            return false
        }
        backgroundStartAfterSetup = false
        return AssistRuntime.ensureService()
    }

    private fun hardwarePermissionsReady(): Boolean =
        PermissionRequirements.bluetoothGranted(this) &&
            PermissionRequirements.bluetoothState(this) == PermissionRequirements.BluetoothState.ENABLED &&
            PermissionRequirements.legacyLocationEnabled(this) != false &&
            PermissionRequirements.notificationsGranted(this)

    private fun openBackgroundSettings() {
        AlertDialog.Builder(this).setTitle("允许助手在后台接收指令")
            .setMessage("华为手机：在手机管家 → 应用启动管理中找到“语音生活助手”，改为手动管理，并允许自启动、关联启动和后台活动。\n\n请保留助手的持续运行通知。日常可返回桌面或切到其他 App；系统“强行停止”后需重新打开助手。")
            .setPositiveButton("应用系统设置") { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
            .setNeutralButton("电池后台设置") { _, _ ->
                val target = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                runCatching { startActivity(target) }.onFailure {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }
            }.setNegativeButton("知道了", null).show()
    }

    private fun saveDashboardContact(request: JSONObject, requestId: String) {
        val alias = request.optString("name").trim().replace(Regex("\\s+"), "")
        val phone = request.optString("phone").trim().replace(Regex("[\\s()（）-]"), "")
        if (!Regex("[\\p{L}\\p{N}]{1,20}").matches(alias) || !Regex("\\+?[0-9]{7,15}").matches(phone)) {
            sendDashboardMessage(JSONObject().put("type", "result").put("request_id", requestId)
                .put("ok", false).put("message", "请填写称呼和有效电话号码"))
            return
        }
        contacts.edit().putString("contact:$alias", phone).apply()
        refreshContacts()
        log("联系人已从首页设置：$alias")
        recordDashboard("设置", "联系人已保存", "已保存联系人 $alias", "本机已保存")
        sendDashboardMessage(JSONObject().put("type", "result").put("request_id", requestId)
            .put("ok", true).put("message", "联系人已保存"))
        publishDashboardState()
    }

    private fun dashboardState(requestId: String? = null): JSONObject {
        val savedContacts = contacts.all.filterKeys { it.startsWith("contact:") }
            .toSortedMap()
        val firstContact = savedContacts.entries.firstOrNull()
        val contactJson = JSONObject().put("name", firstContact?.key?.removePrefix("contact:") ?: "")
            .put("phone", firstContact?.value ?: "")
        val contactsJson = JSONArray().apply {
            savedContacts.forEach { (key, value) ->
                put(JSONObject().put("name", key.removePrefix("contact:")).put("phone", value.toString()))
            }
        }
        val records = JSONArray().apply { dashboardRecords.forEach { put(it) } }
        val bleAllowed = PermissionRequirements.bluetoothGranted(this)
        val missingPermissions = PermissionRequirements.missingNames(this)
        val setupComplete = PermissionRequirements.setupComplete(this)
        val permissionSummary = PermissionRequirements.permissionsSummary(this)
        val permissions = JSONObject()
            .put("call", checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED)
            .put("ble", bleAllowed)
            .put("bluetooth", bleAllowed)
            .put("notifications", PermissionRequirements.notificationsGranted(this))
            .put("accessibility", PermissionRequirements.accessibilityGranted(this))
            .put("bluetoothEnabled", PermissionRequirements.bluetoothState(this) == PermissionRequirements.BluetoothState.ENABLED)
            .put("locationEnabled", PermissionRequirements.legacyLocationEnabled(this) != false)
            .put("summary", permissionSummary)
            .put("missing", JSONArray(missingPermissions))
            .put("setupComplete", setupComplete)
        return JSONObject().put("type", "state")
            .put("request_id", requestId ?: JSONObject.NULL)
            .put("connected", AssistRuntime.initialized && ble.isReady)
            .put("status", if (::status.isInitialized) status.text.toString() else "正在启动")
            .put("analysisStatus", if (::analysisStatus.isInitialized) analysisStatus.text.toString() else "等待硬件录音")
            .put("syncStatus", if (::syncStatus.isInitialized) syncStatus.text.toString() else "本机记录服务正在启动")
            .put("contact", contactJson)
            .put("contacts", contactsJson)
            .put("records", records)
            .put("lastResult", dashboardRecords.firstOrNull { it.optBoolean("isResult") } ?: JSONObject.NULL)
            .put("logs", JSONArray().apply { logs.toList().asReversed().forEach { put(it) } })
            .put("bleStage", AssistRuntime.bleMessage)
            .put("bleScanning", ble.isScanning)
            .put("bleConnecting", ble.isConnecting)
            .put("bleMtu", ble.currentMtu)
            .put("bleDevices", JSONArray().apply { dashboardDevices.forEach { (address, item) ->
                put(JSONObject().put("address", address).put("name", item.second))
            } })
            .put("bleRememberedName", AssistRuntime.hardwareConnection.rememberedName)
            .put("bleRememberedAddress", AssistRuntime.hardwareConnection.rememberedAddress)
            .put("bleAutoReconnect", AssistRuntime.hardwareConnection.autoReconnect)
            .put("backgroundHardwareEnabled", AssistRuntime.hardwareConnection.enabled)
            .put("backgroundHardwareRunning", AssistRuntime.backgroundServiceRunning)
            .put("voiceStage", AssistRuntime.voiceStage)
            .put("voiceProgressText", AssistRuntime.voiceProgressText)
            .put("voicePercent", AssistRuntime.voicePercent ?: JSONObject.NULL)
            .put("voiceReceivedBytes", AssistRuntime.voiceReceivedBytes)
            .put("voiceTotalBytes", AssistRuntime.voiceTotalBytes)
            .put("voiceBytesPerSecond", AssistRuntime.voiceBytesPerSecond)
            .put("transcriptText", AssistRuntime.lastTranscript)
            .put("asrReady", AssistRuntime.asrReady)
            .put("asrStatus", AssistRuntime.asrStatus)
            .put("permissions", permissions)
            .put("permissionSummary", permissionSummary)
            .put("missingPermissions", JSONArray(missingPermissions))
            .put("setupComplete", setupComplete)
            .put("installedAppCount", installedAppCount)
            .put("appCatalogStatus", appCatalogStatus)
    }

    private fun publishDashboardState() {
        if (!dashboardLoaded || !::dashboardWebView.isInitialized || !dashboardVisible || disposed) return
        sendDashboardMessage(dashboardState())
    }

    /** Replace the in-memory preview with durable SQLite history once it is available. */
    private fun refreshDashboardRecordsFromStore() {
        if (disposed || !::backend.isInitialized) return
        backend.recent(40) { stored ->
            main.post {
                if (disposed) return@post
                // A startup read may race the first insert. Keep the immediately visible
                // preview until the insert callback asks us to read again.
                if (stored.isNotEmpty() || dashboardRecords.isEmpty()) {
                    dashboardRecords.clear()
                    dashboardRecords.addAll(stored.map(::dashboardRecord))
                    publishDashboardState()
                }
            }
        }
    }

    private fun dashboardRecord(event: StoredBackendEvent): JSONObject {
        val payload = event.payload
        val type = payload.optString("type")
        val source = when {
            payload.optString("source") == "button_A" -> "眼镜 A"
            payload.optString("source") == "button_B" -> "眼镜 B"
            payload.optString("source") == "local_asr" || payload.optString("source") == "ble_audio" -> "BLE 语音"
            payload.optString("source") == "ble" -> "BLE 硬件"
            payload.optString("source") == "ble_text_v1" -> "眼镜文字 v1"
            payload.optString("source").startsWith("debug") -> "手机操作"
            else -> payload.optString("source").ifBlank { "本机" }
        }
        val title = when {
            type == "audio_transcription" -> "离线转写完成"
            type == "audio_transcription_failed" -> "录音识别失败"
            type == "action_result" -> "手机操作"
            type == "assist_result" -> "页面帮助建议"
            type == "vision_requested" -> "等待眼镜照片"
            type == "command_received" -> "收到语音指令文字"
            type == "hardware_status" -> "硬件状态"
            type == "image_ready" -> "收到图片事件"
            type.endsWith("_received") -> "收到硬件事件"
            type.isNotBlank() -> type
            else -> "本机事件"
        }
        val text = payload.optString("text")
        val result = payload.optString("result")
        val detail = when {
            payload.optJSONObject("data")?.optString("reply").orEmpty().isNotBlank() ->
                listOf(text, payload.getJSONObject("data").optString("reply")).filter { it.isNotBlank() }.joinToString("\n")
            text.isNotBlank() && result.isNotBlank() && result != "received" -> "$text\n$result"
            text.isNotBlank() -> text
            result.isNotBlank() -> result
            else -> "事件编号：${payload.optString("event_id", event.id)}"
        }
        val time = runCatching {
            val occurred = payload.optString("occurred_at")
            if (occurred.isBlank()) error("missing time")
            DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.CHINA)
                .format(Instant.parse(occurred).atZone(ZoneId.systemDefault()))
        }.getOrElse { SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(event.createdMs)) }
        return JSONObject()
            .put("id", event.id)
            .put("time", time)
            .put("source", source)
            .put("isResult", type in setOf("action_result", "assist_result", "audio_transcription_failed"))
            .put("title", title.take(120))
            .put("detail", detail.take(500))
            .put("status", result.take(80))
            .put("synced", event.synced)
    }

    private fun sendDashboardMessage(message: JSONObject) {
        if (!::dashboardWebView.isInitialized || !dashboardVisible || disposed) return
        dashboardWebView.evaluateJavascript("window.onNativeMessage && window.onNativeMessage(${message})", null)
    }

    private fun recordDashboard(source: String, title: String, detail: String, result: String = "") {
        val record = JSONObject()
            .put("id", UUID.randomUUID().toString())
            .put("time", SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date()))
            .put("source", source)
            .put("title", title.take(120))
            .put("detail", detail.take(500))
            .put("status", result.take(80))
            .put("synced", false)
        dashboardRecords.add(0, record)
        if (dashboardRecords.size > 40) dashboardRecords.removeAt(dashboardRecords.lastIndex)
        publishDashboardState()
    }

    private fun requestBleScan() {
        if (!hardwarePermissionsReady()) {
            repairPermissions()
            return
        }
        scan()
    }

    private fun scan() {
        if (PermissionRequirements.bluetoothState(this) != PermissionRequirements.BluetoothState.ENABLED ||
            PermissionRequirements.legacyLocationEnabled(this) == false) {
            repairPermissions(); return
        }
        if (!startBackgroundHardware()) return
        AssistRuntime.hardwareConnection.scanningStarted()
        discovered.clear(); dashboardDevices.clear(); deviceList.removeAllViews()
        ble.scan()
        publishDashboardState()
    }

    private fun connectHardware(device: BluetoothDevice): Boolean {
        val config = runCatching { BleSettings(UUID.fromString(serviceInput.text.toString().trim()),
            UUID.fromString(notifyInput.text.toString().trim()), UUID.fromString(writeInput.text.toString().trim())) }.getOrNull()
        if (config == null) { toast("UUID 格式不正确，请向硬件同学核对"); return false }
        if (!startBackgroundHardware()) return false
        prefs.edit().putString("ble_service", config.service.toString()).putString("ble_notify", config.notify.toString())
            .putString("ble_write", config.write.toString()).apply()
        AssistRuntime.hardwareConnection.connect(device, config)
        publishDashboardState()
        return true
    }

    private fun showDevice(device: BluetoothDevice, name: String) {
        if (disposed || discovered.size >= 40 || !discovered.add(device.address)) return
        dashboardDevices[device.address] = device to name
        deviceList.addView(button("连接 $name　${device.address}") {
            connectHardware(device)
        })
        publishDashboardState()
    }

    private fun refreshPhoneStatus() {
        runCatching { appDirectory.listLaunchableApps() }.onSuccess { apps ->
            installedAppCount = apps.distinctBy { it.packageName }.size
            appCatalogStatus = "已读取 $installedAppCount 个可启动应用；请说手机图标显示的名称"
        }.onFailure {
            installedAppCount = -1
            appCatalogStatus = "手机应用目录读取失败，请返回助手后重试"
        }
        permissionStatus.text = "${PermissionRequirements.permissionsSummary(this)}\n$appCatalogStatus\n打开应用无需逐个授权或无障碍权限；电话无需读取通讯录。"
        publishDashboardState()
    }

    private fun openPermissionSetup() {
        stopListening()
        startActivity(Intent(this, PermissionSetupActivity::class.java))
    }

    private fun repairPermissions() {
        stopListening()
        PermissionRecovery.open(this, PermissionRequirements.permissionsSummary(this), autoStart = true)
    }

    private fun showInstalledApps() {
        runCatching { appDirectory.listLaunchableApps() }.onSuccess { apps ->
            val names = apps.distinctBy { it.label }.joinToString("、") { it.label }
            showDetails("手机中的可启动应用", if (names.isBlank()) "暂未找到可启动应用。" else
                "说“打开”或“启动”，加上下面的名称即可，无需逐个录入。\n\n$names\n\n同名应用不会自动猜选；这里只列出系统允许助手发现的启动入口。")
        }.onFailure { feedback("无法读取手机应用目录，请稍后重试", error = true) }
    }

    private fun debugCommand(text: String) {
        execute(Action(UUID.randomUUID().toString(), "debug_text", text))
    }

    private fun stopPlayback() { player?.release(); player = null }

    private fun playLastRecording() {
        val file = lastRecording
        if (file?.isFile != true) { toast("还没有收到录音"); return }
        if (audioAnalysisInFlight) { toast("请等待识别结束再试听"); return }
        stopListening(); stopPlayback()
        try {
            val media = MediaPlayer(); player = media
            media.setOnCompletionListener { stopPlayback(); feedback("试听结束；请核对是否有清晰人声") }
            media.setOnErrorListener { _, what, extra ->
                stopPlayback(); feedback("手机无法播放录音（$what/$extra），请检查 WAV 编码", error = true); true
            }
            media.setDataSource(file.absolutePath)
            media.setOnPreparedListener { if (active && player === it) { it.start(); status.text = "正在试听上一段录音，请调高媒体音量" } else stopPlayback() }
            media.prepareAsync()
        } catch (error: Exception) { stopPlayback(); feedback("录音试听失败：${error.message}", error = true) }
    }

    private fun redact(text: String): String {
        val secrets = listOf(prefs.getString("token", "").orEmpty(), AssistRuntime.apiToken, AssistRuntime.ocrToken,
            AssistRuntime.unifiedVisionToken, AssistRuntime.medicineToken, AssistRuntime.bAgentToken)
        return secrets.filter { it.isNotBlank() }.fold(text) { result, secret -> result.replace(secret, "[已隐藏令牌]") }
    }

    private fun showReport() {
        val config = backendSettings()
        val safeUrl = if (config.endpoint.isBlank()) "未配置（记录仅保存在手机）" else runCatching {
            val uri = URI(config.endpoint); URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null).toString()
        }.getOrDefault("地址无效")
        showDetails("联调报告（可复制）", redact("版本 ${BuildConfig.VERSION_NAME}\nA 输入：硬件文字帧 v1/en；兼容手机离线 Vosk WAV 识别\n记录接口：$safeUrl\n${permissionStatus.text}\n最近录音：${lastRecording?.length() ?: 0} 字节\n\n当前阶段：${analysisStatus.text}\n\n同步：${syncStatus.text}\n\n日志：\n${logs.joinToString("\n")}"))
    }

    private fun showDetails(title: String, message: String) {
        val text = label(message, 15f).apply { setTextIsSelectable(true); setPadding(dp(18), dp(10), dp(18), dp(10)) }
        AlertDialog.Builder(this).setTitle(title).setView(ScrollView(this).apply { addView(text) })
            .setPositiveButton("知道了", null).setNeutralButton("复制内容") { _, _ ->
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("联调报告", message))
                toast("已复制；可自行粘贴给后端同学")
            }.show()
    }

    private fun execute(action: Action) {
        stopListening(); stopPlayback()
        heard.text = "收到指令：${action.text}"
        AssistRuntime.command(action.id, action.text, action.source)
    }

    private fun backendSettings() = BackendSettings(prefs.getString("endpoint", "").orEmpty(),
        prefs.getString("token", "").orEmpty(), prefs.getString("device_id", "").orEmpty())

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        PermissionRequirements.markRequested(this, permissions.zip(results.toList()).map { it.first })
        val granted = results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }
        when (code) {
            BLE_PERMISSION -> if (granted) scan() else bleStatus.setText("未允许 BLE 权限，请到手机设置授权后再搜索")
            MIC_PERMISSION -> if (granted) scheduleListening() else { phoneMic.isChecked = false; status.text = "未启用手机麦克风；仍可离线识别硬件 WAV" }
            CALL_SETUP_PERMISSION -> { refreshPhoneStatus(); feedback(if (granted) "电话权限已开启，尚未拨号" else "未开启电话权限，可到系统设置中允许", error = !granted) }
        }
        publishDashboardState()
    }

    private fun canListen() = active && !disposed && phoneMic.isChecked && !handling && !externalAction && player == null && !audioAnalysisInFlight

    private fun startListening() {
        if (!canListen() || listening || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            phoneMic.isChecked = false; status.text = "这台手机未提供麦克风识别服务；仍可离线识别硬件 WAV"; return
        }
        if (recognizer == null) recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { if (canListen()) status.text = "正在听，请说指令" }
                override fun onBeginningOfSpeech() { if (canListen()) status.text = "听到了，请继续说" }
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() { if (canListen()) status.text = "正在识别…" }
                override fun onError(error: Int) {
                    val expected = listening; listening = false
                    if (!expected || !canListen()) return
                    if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) voiceErrors++
                    if (voiceErrors >= 3) { phoneMic.isChecked = false; status.text = "语音服务连续出错（$error），可检查服务后重新勾选" }
                    else scheduleListening()
                }
                override fun onResults(results: Bundle?) {
                    val expected = listening; listening = false
                    if (!expected || !canListen()) return
                    voiceErrors = 0
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (text.isBlank()) { scheduleListening(); return }
                    val action = Action(UUID.randomUUID().toString(), "phone_mic", text)
                    execute(action)
                }
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
        }
        val request = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        try { listening = true; recognizer?.startListening(request) }
        catch (_: Exception) { listening = false; phoneMic.isChecked = false; status.text = "语音识别无法启动，仍可使用 BLE 指令" }
    }

    private fun stopListening() { main.removeCallbacks(listenAgain); listening = false; recognizer?.cancel() }
    private fun scheduleListening() { main.removeCallbacks(listenAgain); if (canListen()) main.postDelayed(listenAgain, 800) }

    private fun feedback(text: String, error: Boolean = false) {
        if (disposed) return
        status.text = text
        publishDashboardState()
        if (active) {
            val vibrated = runCatching {
                val vibrator = if (Build.VERSION.SDK_INT >= 31) {
                    getSystemService(VibratorManager::class.java)?.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    (getSystemService(VIBRATOR_SERVICE) as? Vibrator)
                }
                if (vibrator?.hasVibrator() != true) false else {
                    val effect = if (error) {
                        VibrationEffect.createWaveform(longArrayOf(0, 100, 100, 100), -1)
                    } else {
                        VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE)
                    }
                    vibrator.vibrate(effect)
                    true
                }
            }.getOrDefault(false)
            if (!vibrated) toast(text)
        }
        scheduleListening()
    }

    override fun onResume() {
        super.onResume(); active = true; externalAction = false; AssistRuntime.homeForeground = true
        OcrResultDialog.attach(this)
        AContactDialog.attach(this)
        if (dashboardVisible && ::dashboardWebView.isInitialized) dashboardWebView.onResume()
        refreshPhoneStatus()
        if (::backend.isInitialized) { backend.retry(); main.removeCallbacks(periodicSync); main.postDelayed(periodicSync, 15000) }
        if (backgroundStartAfterSetup && hardwarePermissionsReady()) {
            backgroundStartAfterSetup = false
            AssistRuntime.ensureService()
        } else if (AssistRuntime.hardwareConnection.enabled && PermissionRequirements.bluetoothGranted(this)) AssistRuntime.ensureService()
        if (::phoneMic.isInitialized) scheduleListening()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && active && !disposed) {
            publishDashboardState()
            PermissionRecovery.showPending(this)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("background_start_after_setup", backgroundStartAfterSetup)
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        OcrResultDialog.detach(this)
        AContactDialog.detach(this)
        foregroundGeneration++
        active = false; AssistRuntime.homeForeground = false; stopListening()
        if (::dashboardWebView.isInitialized) dashboardWebView.onPause()
        stopPlayback()
        main.removeCallbacks(periodicSync)
        if (AssistRuntime.initialized) ble.stopScan()
        super.onPause()
    }

    @Deprecated("Use the dashboard navigation to leave secondary settings")
    override fun onBackPressed() {
        if (!dashboardVisible) showDashboard() else super.onBackPressed()
    }

    override fun onDestroy() {
        disposed = true
        AssistRuntime.removeListener(assistObserver)
        main.removeCallbacksAndMessages(null); recognizer?.destroy()
        stopPlayback(); backend.close()
        if (::dashboardWebView.isInitialized) {
            dashboardWebView.apply {
                stopLoading(); loadUrl("about:blank"); removeJavascriptInterface("AndroidApp")
                webViewClient = WebViewClient(); removeAllViews(); destroy()
            }
        }
        super.onDestroy()
    }

    private fun log(message: String) {
        if (disposed) return
        if (logs.size >= 35) logs.removeFirst()
        logs.addLast(redact(message)); logView.text = logs.joinToString("\n")
        publishDashboardState()
    }
    private fun label(text: String, size: Float) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(0xFF23443E.toInt()); setPadding(0, dp(5), 0, dp(5))
    }
    private fun input(hint: String, value: String) = EditText(this).apply {
        this.hint = hint; setText(value); setSingleLine(true); textSize = 15f
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    }
    private fun button(text: String, action: () -> Unit) = Button(this).apply { this.text = text; setOnClickListener { action() } }
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()

    companion object {
        private const val DASHBOARD_ORIGIN = "https://voice-life-dashboard.local/"
        private const val BLE_PERMISSION = 51
        private const val MIC_PERMISSION = 52
        private const val CALL_SETUP_PERMISSION = 54
        private const val NOTIFICATION_PERMISSION = 55
    }
}
