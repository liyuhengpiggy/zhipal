package cn.seniorvoice.demo

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

data class BleSettings(val service: UUID, val notify: UUID, val write: UUID)

/** GATT setup runs on main; ordered receive/framing and disk writes run on one worker. */
@SuppressLint("MissingPermission")
class BleClient(
    private val context: Context,
    private val onStatus: (String) -> Unit,
    private val onDevice: (BluetoothDevice, String) -> Unit,
    private val onEvent: (DeviceEvent) -> Unit,
    private val onWav: (File) -> Unit,
    private val onReceiveProgress: () -> Unit = {},
    private val onDisconnect: () -> Unit = {},
    private val onWavStarted: () -> Unit = {},
    private val onTextFrame: (BleTextFrame) -> Unit = {},
    private val onReady: () -> Unit = {},
    private val onTransferProgress: (BleReceiveProgress) -> Unit = {},
    private val onScanFinished: () -> Unit = {}
) {
    private val main = Handler(Looper.getMainLooper())
    private val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private val preferences = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var config: BleSettings? = null
    private var writer: BluetoothGattCharacteristic? = null
    private var notification: BluetoothGattCharacteristic? = null
    private var notificationValue: ByteArray? = null
    private var mtuPending = false
    private var subscriptionAttempts = 0
    @Volatile private var scanning = false
    @Volatile private var connecting = false
    @Volatile private var ready = false
    @Volatile private var negotiatedMtu = 23
    @Volatile private var closed = false
    @Volatile private var receiveGeneration = 0L
    @Volatile private var lastReceivedAt = 0L
    private val receiveWorker = Executors.newSingleThreadExecutor()
    // The following receive state is touched only by receiveWorker.
    private var workerGeneration = 0L
    private var lastProgressAt = 0L
    private var wavStartedAt = 0L
    private var lastWavProgressAt = 0L
    private val incoming: BleIncomingStream = BleIncomingStream(context.cacheDir, { line ->
        val generation = workerGeneration
        val parsed = runCatching { DeviceEvent.parse(line) }
        main.post {
            if (isCurrent(generation)) {
                parsed.onSuccess(onEvent).onFailure { onStatus("消息格式错误：${it.message}") }
            }
        }
    }, { file ->
        val generation = workerGeneration
        main.post {
            if (isCurrent(generation)) onWav(file)
            else file.delete()
        }
    }, { message ->
        val generation = workerGeneration
        main.post { if (isCurrent(generation)) onStatus(message) }
    }, {
        val generation = workerGeneration
        wavStartedAt = SystemClock.elapsedRealtime()
        lastWavProgressAt = 0L
        main.post {
            if (isCurrent(generation)) {
                requestFastConnection()
                onWavStarted()
            }
        }
    }, { frame ->
        val generation = workerGeneration
        main.post { if (isCurrent(generation)) onTextFrame(frame) }
    }, ::publishWavProgress)
    private val outgoing = ArrayDeque<ByteArray>()
    private var writeInFlight = false
    var peerId: String = ""
        private set

    private fun publishWavProgress(received: Long, total: Long, complete: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (lastWavProgressAt == 0L || complete || now - lastWavProgressAt >= PROGRESS_INTERVAL_MS) {
            lastWavProgressAt = now
            val generation = workerGeneration
            val progress = BleReceiveProgress(received, total, (now - wavStartedAt).coerceAtLeast(0L), incoming.wavFirstBytesHex, complete)
            main.post { if (isCurrent(generation)) onTransferProgress(progress) }
        }
    }

    val isReady: Boolean get() = ready
    val isConnecting: Boolean get() = connecting
    val isScanning: Boolean get() = scanning
    val currentMtu: Int get() = negotiatedMtu
    val savedPeerId: String get() = preferences.getString("ble_last_address", "").orEmpty()

    private fun isCurrent(generation: Long) = !closed && generation == receiveGeneration

    private val scanTimeout = Runnable { finishScan("搜索结束；请在下方选择设备，或重新搜索") }
    private val connectionTimeout = Runnable { fail("等待硬件连接超时，请检查板子供电或是否正被其他蓝牙软件占用") }
    private val writeTimeout = Runnable { fail("回传超时，请重新连接硬件") }
    private val mtuTimeout = Runnable {
        if (connecting && mtuPending) {
            mtuPending = false
            onStatus("硬件未回复 MTU 协商，按当前 MTU $negotiatedMtu 继续订阅")
            subscribeNotifications()
        }
    }
    private val subscriptionRetry = Runnable { subscribeNotifications() }
    // One periodic watchdog replaces removing/posting a main-thread timer for every BLE packet.
    private val receiveTimeout = object : Runnable {
        override fun run() {
            if (closed || !ready) return
            val generation = receiveGeneration
            val now = SystemClock.elapsedRealtime()
            if (lastReceivedAt > 0 && now - lastReceivedAt >= RECEIVE_IDLE_TIMEOUT_MS) {
                submitReceive {
                    if (isCurrent(generation) && SystemClock.elapsedRealtime() - lastReceivedAt >= RECEIVE_IDLE_TIMEOUT_MS && incoming.hasPendingFrame) {
                        incoming.reset()
                        main.post {
                            if (isCurrent(generation)) onStatus("蓝牙消息接收中断超过 30 秒，已清除未完成数据，请重新发送")
                        }
                    }
                }
            }
            main.postDelayed(this, 5000)
        }
    }

    private val scanner = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            main.post {
                if (!scanning || closed) return@post
                val name = result.scanRecord?.deviceName ?: runCatching { result.device.name }.getOrNull() ?: "未命名 BLE"
                onDevice(result.device, name)
            }
        }
        override fun onScanFailed(errorCode: Int) { main.post { finishScan("BLE 搜索失败：$errorCode") } }
    }

    fun scan() {
        if (closed) return
        stopScan()
        try {
            val deviceAdapter = adapter
            if (deviceAdapter == null) { finishScan("这台设备不支持蓝牙"); return }
            if (!deviceAdapter.isEnabled) { finishScan("请先在手机设置中打开蓝牙"); return }
            val leScanner = deviceAdapter.bluetoothLeScanner
            if (leScanner == null) { finishScan("无法启动 BLE 搜索"); return }
            scanning = true
            leScanner.startScan(scanner)
            main.postDelayed(scanTimeout, 12000)
            onStatus("正在搜索；首次使用请选择自己的硬件，连接成功后会自动记住")
        } catch (_: SecurityException) {
            finishScan("没有蓝牙权限，请允许附近设备；Android 11 及以下还需要定位权限和打开定位")
        }
    }

    private fun finishScan(message: String) {
        val wasScanning = scanning
        stopScan()
        onStatus(message)
        if (!wasScanning) onScanFinished()
    }

    fun stopScan() {
        main.removeCallbacks(scanTimeout)
        val wasScanning = scanning
        if (wasScanning) runCatching { adapter?.bluetoothLeScanner?.stopScan(scanner) }
        scanning = false
        if (wasScanning) onScanFinished()
    }

    fun connect(device: BluetoothDevice, settings: BleSettings) = connectInternal(device, settings, autoConnect = false)

    /** Only a previously subscribed device is eligible. This never binds an arbitrary nearby board. */
    fun connectSaved(settings: BleSettings): Boolean {
        if (closed || connecting || ready) return false
        val address = savedPeerId
        if (!BluetoothAdapter.checkBluetoothAddress(address)) return false
        return try {
            val deviceAdapter = adapter ?: return false
            if (!deviceAdapter.isEnabled) return false
            connectInternal(deviceAdapter.getRemoteDevice(address), settings, autoConnect = true)
            connecting
        } catch (_: SecurityException) {
            onStatus("自动重连需要附近设备权限")
            false
        } catch (_: IllegalArgumentException) { false }
    }

    private fun connectInternal(device: BluetoothDevice, settings: BleSettings, autoConnect: Boolean) {
        if (closed) return
        stopScan()
        disconnectInternal(notify = false)
        config = settings
        try {
            peerId = device.address
            connecting = true
            negotiatedMtu = 23
            onStatus(if (autoConnect) "正在自动等待已记住的硬件上线…" else "正在连接 ${device.name ?: peerId}…")
            gatt = device.connectGatt(context.applicationContext, autoConnect, callbacks, BluetoothDevice.TRANSPORT_LE)
            if (gatt == null) { fail("无法创建蓝牙连接"); return }
            main.postDelayed(connectionTimeout, if (autoConnect) 90000L else 20000L)
        } catch (_: SecurityException) { fail("需要允许附近设备权限") }
        catch (error: Exception) { fail("无法连接蓝牙：${error.message ?: "系统暂不可用"}") }
    }

    private val callbacks = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (g !== gatt || closed) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) { fail("蓝牙连接出错：$status"); return@post }
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    // A waiting autoConnect session gets a fresh setup deadline once the board appears.
                    main.removeCallbacks(connectionTimeout)
                    main.postDelayed(connectionTimeout, 20000)
                    requestFastConnection()
                    onStatus("已连接，正在查找约定的 BLE 服务…")
                    if (!g.discoverServices()) fail("无法读取 BLE 服务")
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) fail("硬件已断开")
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post {
                if (g !== gatt || closed) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) { fail("读取 BLE 服务失败：$status"); return@post }
                val settings = config ?: return@post
                val service = g.getService(settings.service)
                    ?: run { fail("找不到指定服务，请核对 Service UUID"); return@post }
                val notify = service.getCharacteristic(settings.notify)
                    ?: run { fail("找不到指定通知通道，请核对 Notify UUID"); return@post }
                notificationValue = when {
                    notify.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    notify.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                    else -> { fail("指定通道不支持 Notify/Indicate"); return@post }
                }
                notification = notify
                writer = service.getCharacteristic(settings.write)?.takeIf {
                    it.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
                }
                subscriptionAttempts = 0
                // MTU and CCCD writes are serialized; simultaneous GATT operations can be rejected.
                mtuPending = runCatching { g.requestMtu(TARGET_MTU) }.getOrDefault(false)
                if (mtuPending) {
                    onStatus("正在协商 BLE 传输大小…")
                    main.postDelayed(mtuTimeout, 2500)
                } else subscribeNotifications()
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            main.post {
                if (g !== gatt || closed) return@post
                if (status == BluetoothGatt.GATT_SUCCESS && mtu >= 23) negotiatedMtu = mtu
                if (mtuPending) {
                    mtuPending = false
                    main.removeCallbacks(mtuTimeout)
                    subscribeNotifications()
                }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            main.post {
                if (g !== gatt || closed || descriptor.uuid != CCCD || descriptor.characteristic.uuid != config?.notify) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) { fail("硬件拒绝通知订阅：$status"); return@post }
                if (ready) return@post
                main.removeCallbacks(connectionTimeout)
                main.removeCallbacks(subscriptionRetry)
                connecting = false
                ready = true
                val settings = config ?: return@post
                preferences.edit().putString("ble_last_address", peerId)
                    .putString("ble_last_name", runCatching { g.device.name }.getOrNull() ?: peerId)
                    .putString("ble_service", settings.service.toString())
                    .putString("ble_notify", settings.notify.toString())
                    .putString("ble_write", settings.write.toString()).apply()
                main.removeCallbacks(receiveTimeout)
                main.postDelayed(receiveTimeout, 5000)
                onStatus(if (writer == null) "硬件已就绪 · MTU $negotiatedMtu · 仅接收（无可写通道）" else "硬件已就绪 · MTU $negotiatedMtu · 可双向收发")
                onReady()
            }
        }

        @Deprecated("Used on Android 12 and earlier")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val copy = characteristic.value?.copyOf() ?: return
            acceptNotification(g, characteristic.uuid, copy)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            acceptNotification(g, characteristic.uuid, value.copyOf())
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            main.post {
                if (g !== gatt || !writeInFlight || closed) return@post
                main.removeCallbacks(writeTimeout)
                writeInFlight = false
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    outgoing.clear(); onStatus("回传失败：$status"); return@post
                }
                outgoing.pollFirst()
                writeNext()
            }
        }
    }

    private fun subscribeNotifications() {
        val g = gatt ?: return
        val notify = notification ?: return
        val value = notificationValue ?: return
        if (closed || ready || mtuPending) return
        val cccd = notify.getDescriptor(CCCD)
        val queued = runCatching {
            if (cccd == null || !g.setCharacteristicNotification(notify, true)) return@runCatching false
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value = value
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
        }.getOrDefault(false)
        subscriptionAttempts++
        when {
            queued -> onStatus("MTU $negotiatedMtu，正在订阅硬件通知…")
            cccd == null -> fail("不能订阅通知，请让硬件提供 0x2902 描述符")
            subscriptionAttempts < 4 -> main.postDelayed(subscriptionRetry, 300)
            else -> fail("订阅请求发送失败，请检查蓝牙权限和硬件状态")
        }
    }

    private fun requestFastConnection() {
        runCatching { gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }
    }

    private fun acceptNotification(g: BluetoothGatt, uuid: UUID, bytes: ByteArray) {
        // No per-packet main.post: the Bluetooth callback queues bytes directly to the ordered worker.
        val generation = receiveGeneration
        if (g !== gatt || closed || uuid != config?.notify || bytes.isEmpty()) return
        lastReceivedAt = SystemClock.elapsedRealtime()
        submitReceive {
            if (g !== gatt || !isCurrent(generation)) return@submitReceive
            workerGeneration = generation
            val now = SystemClock.elapsedRealtime()
            if (now - lastProgressAt >= PROGRESS_INTERVAL_MS) {
                lastProgressAt = now
                main.post { if (isCurrent(generation)) onReceiveProgress() }
            }
            incoming.accept(bytes)
        }
    }

    private fun submitReceive(block: () -> Unit) {
        try { receiveWorker.execute(block) }
        catch (_: RejectedExecutionException) { /* Client was closed while Bluetooth delivered a callback. */ }
    }

    fun send(line: String): Boolean {
        if (!ready || writer == null) return false
        val bytes = (line.trimEnd('\r', '\n') + "\n").toByteArray(Charsets.UTF_8)
        // MTU negotiation proves ATT capacity, not the firmware's control-message buffer.
        // Preserve the existing 20-byte phone TX framing until firmware advertises larger writes.
        val packetSize = 20
        if (bytes.size > 8192 || outgoing.size + (bytes.size + packetSize - 1) / packetSize > 300) return false
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + packetSize, bytes.size)
            outgoing.add(bytes.copyOfRange(offset, end))
            offset = end
        }
        writeNext()
        return true
    }

    private fun writeNext() {
        val g = gatt ?: return
        val characteristic = writer ?: return
        if (!ready || writeInFlight || outgoing.isEmpty()) return
        val packet = outgoing.peekFirst() ?: return
        writeInFlight = true
        val queued = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(characteristic, packet, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = packet
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                g.writeCharacteristic(characteristic)
            }
        }.getOrDefault(false)
        if (!queued) {
            outgoing.clear(); writeInFlight = false; onStatus("无法回传，请检查可写特征值和权限")
        } else main.postDelayed(writeTimeout, 5000)
    }

    fun disconnect() = disconnectInternal(notify = true)

    private fun disconnectInternal(notify: Boolean) {
        main.removeCallbacks(connectionTimeout)
        main.removeCallbacks(writeTimeout)
        main.removeCallbacks(receiveTimeout)
        main.removeCallbacks(mtuTimeout)
        main.removeCallbacks(subscriptionRetry)
        receiveGeneration++
        val previous = gatt
        gatt = null
        ready = false
        connecting = false
        mtuPending = false
        notification = null
        notificationValue = null
        writer = null
        writeInFlight = false
        lastReceivedAt = 0L
        submitReceive {
            incoming.reset()
            lastProgressAt = 0L
            lastWavProgressAt = 0L
            wavStartedAt = 0L
        }
        outgoing.clear()
        runCatching { previous?.disconnect() }
        runCatching { previous?.close() }
        if (notify) onDisconnect()
    }

    private fun fail(message: String) { disconnect(); onStatus(message) }

    // Keep posted WAV callbacks alive so they can delete files from a closed session.
    fun close() { closed = true; stopScan(); disconnect(); receiveWorker.shutdown() }

    companion object {
        const val DEFAULT_SERVICE = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
        const val DEFAULT_NOTIFY = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"
        const val DEFAULT_WRITE = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
        private const val TARGET_MTU = 247
        private const val PROGRESS_INTERVAL_MS = 250L
        private const val RECEIVE_IDLE_TIMEOUT_MS = 30000L
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
