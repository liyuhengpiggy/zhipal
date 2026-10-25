package cn.seniorvoice.demo

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Reassembles JSON objects across notifications; runs on the BLE receive worker. */
class BleJsonFramer(
    private val cacheDirectory: File,
    private val onMessage: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val maxFrameBytes: Int = MAX_FRAME_BYTES,
    private val memoryThreshold: Int = 64 * 1024
) {
    private var memory = ByteArrayOutputStream()
    private var spool: File? = null
    private var output: BufferedOutputStream? = null
    private var size = 0
    private var depth = 0
    private var inString = false
    private var escaped = false
    private var discarding = false
    val hasPendingFrame: Boolean get() = size > 0 || discarding

    fun accept(packet: ByteArray) {
        for (byte in packet) acceptByte(byte)
    }

    fun acceptByte(byte: Byte) {
            val value = byte.toInt() and 0xff
            if (discarding) {
                if (value == 10) { discarding = false }
                return
            }
            if (size == 0 && (value == 9 || value == 10 || value == 13 || value == 32)) return
            if (size == 0 && value != '{'.code) {
                reject("文字消息格式无效，请重新发送；WAV 文件由音频接收通道自动识别")
                return
            }
            if (size >= maxFrameBytes) {
                reject("单条 BLE 消息超过 ${maxFrameBytes / (1024 * 1024)} MiB；请缩小每块音频，丢弃后发送换行或重新连接")
                return
            }
            try {
                if (output == null && size >= memoryThreshold) {
                    spool = File.createTempFile("ble-json-", ".tmp", cacheDirectory)
                    val stream = spool!!.outputStream().buffered()
                    output = stream
                    memory.writeTo(stream)
                    memory = ByteArrayOutputStream()
                }
                (output ?: memory).write(value)
                size++
            } catch (_: Exception) {
                reject("无法暂存蓝牙消息，请检查手机可用存储空间")
                return
            }
            if (inString) {
                if (escaped) escaped = false
                else if (value == '\\'.code) escaped = true
                else if (value == '"'.code) inString = false
            } else when (value) {
                '"'.code -> inString = true
                '{'.code, '['.code -> depth++
                '}'.code, ']'.code -> depth--
            }
            if (depth > 64) {
                reject("JSON 嵌套过深，请检查发送格式")
            } else if (depth == 0 && !inString) {
                complete()
            }
    }

    private fun complete() {
        val result = runCatching {
            output?.close()
            output = null
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val file = spool
            if (file != null) InputStreamReader(file.inputStream(), decoder).use { it.readText() }
            else decoder.decode(ByteBuffer.wrap(memory.toByteArray())).toString()
        }
        reset()
        result.onSuccess(onMessage).onFailure { onError("蓝牙消息读取失败或不是有效 UTF-8，请检查录音封装") }
    }

    private fun reject(message: String) {
        reset()
        discarding = true
        onError(message)
    }

    fun reset() {
        runCatching { output?.close() }
        output = null
        spool?.delete()
        spool = null
        memory = ByteArrayOutputStream()
        size = 0; depth = 0; inString = false; escaped = false; discarding = false
    }

    companion object {
        const val MAX_FRAME_BYTES = 4 * 1024 * 1024
    }
}
