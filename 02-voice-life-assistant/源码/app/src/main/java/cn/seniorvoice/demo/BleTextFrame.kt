package cn.seniorvoice.demo

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

data class BleTextFrame(val type: Int, val sequence: Long, val language: String, val text: String)

/** Firmware text_frame.h v1. Worker-thread only; never interpret a damaged body as JSON. */
class BleTextFrameDecoder(
    private val onFrame: (BleTextFrame) -> Unit,
    private val onError: (String) -> Unit
) {
    private val buffer = ByteArray(MAX_BODY + 16)
    private var size = 0
    private var searching = false
    val hasPendingFrame: Boolean get() = size != 0 || searching

    fun acceptByte(byte: Byte) {
        buffer[size++] = byte
        while (size > 0) {
            if (u8(0) != 0xa5) { discard(1); continue }
            if (size < 2) return
            if (u8(1) != 0x5a) { discard(1); continue }
            if (size < 10) return
            val length = u16(8)
            if (length > MAX_BODY) {
                onError("文字帧正文长度超过 1024 字节，请检查固件或请求重发")
                searching = true; discard(1); continue
            }
            val total = length + 16
            if (size < total) return
            if (u8(total - 2) != 13 || u8(total - 1) != 10 ||
                crc16(buffer, 2, length + 10) != u16(total - 4)) {
                onError("文字帧帧尾或 CRC 校验失败，未执行；可请求重发")
                searching = true; discard(1); continue
            }
            // A correctly framed but unsupported message is consumed as one opaque frame.
            val decoded = runCatching {
                require(u8(2) == 1) { "不支持的文字帧版本" }
                val type = u8(3)
                require(type == 1 || type == 2) { "不支持的文字帧类型" }
                val language = String(buffer, 10, 2, Charsets.US_ASCII)
                require(language == if (type == 1) "en" else "--") { "文字帧语言应为 ${if (type == 1) "en" else "--"}" }
                val text = Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(buffer, 12, length)).toString()
                require('\u0000' !in text) { "文字帧正文不能带 NUL 结尾或字符" }
                val sequence = (0..3).fold(0L) { value, i -> value or (u8(4 + i).toLong() shl (8 * i)) }
                BleTextFrame(type, sequence, language, text)
            }
            discard(total)
            searching = false
            decoded.onSuccess(onFrame).onFailure { onError("文字帧未处理：${it.message}") }
        }
    }

    fun reset() { size = 0; searching = false }
    private fun u8(index: Int) = buffer[index].toInt() and 0xff
    private fun u16(index: Int) = u8(index) or (u8(index + 1) shl 8)
    private fun discard(count: Int) { buffer.copyInto(buffer, 0, count, size); size -= count }

    companion object {
        const val MAX_BODY = 1024
        fun crc16(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Int {
            var crc = 0xffff
            for (i in offset until offset + length) {
                crc = crc xor ((bytes[i].toInt() and 0xff) shl 8)
                repeat(8) { crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1 }
                crc = crc and 0xffff
            }
            return crc
        }
    }
}

/** Called on main after the BLE connection-generation check; sequence is shared by both types. */
class BleTextFrameSession(
    private val onText: (BleTextFrame) -> Unit,
    private val onStatus: (BleTextFrame) -> Unit,
    private val onDuplicate: (BleTextFrame) -> Unit = {}
) {
    private val seen = HashSet<Long>()
    fun accept(frame: BleTextFrame) {
        if (!seen.add(frame.sequence)) { onDuplicate(frame); return }
        if (frame.type == 1) onText(frame) else onStatus(frame)
    }
    fun reset() = seen.clear()
}
