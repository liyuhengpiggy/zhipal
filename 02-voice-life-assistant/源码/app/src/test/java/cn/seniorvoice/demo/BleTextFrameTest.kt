package cn.seniorvoice.demo

import cn.seniorvoice.demo.assist.AssistSession
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BleTextFrameTest {
    @get:Rule val temporary = TemporaryFolder()
    private val frames = mutableListOf<BleTextFrame>()
    private val errors = mutableListOf<String>()
    private fun receiver(onFrame: (BleTextFrame) -> Unit = { frames.add(it) }) = BleIncomingStream(
        temporary.newFolder(), { fail("Text body escaped into JSON: $it") }, { fail("Unexpected WAV") },
        { errors.add(it) }, onTextFrame = onFrame)

    private fun frame(text: String = "open QQ", seq: Long = 1, type: Int = 1,
                      lang: String = if (type == 1) "en" else "--", version: Int = 1,
                      body: ByteArray = text.toByteArray(Charsets.UTF_8)): ByteArray {
        val bytes = ByteArray(body.size + 16)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(0xa5.toByte()); put(0x5a); put(version.toByte()); put(type.toByte()); putInt(seq.toInt())
            putShort(body.size.toShort()); put(lang.toByteArray(Charsets.US_ASCII)); put(body)
            putShort(BleTextFrameDecoder.crc16(bytes, 2, body.size + 10).toShort()); put(13); put(10)
        }
        return bytes
    }
    private fun feed(receiver: BleIncomingStream, bytes: ByteArray, chunk: Int = 20) {
        bytes.toList().chunked(chunk).forEach { receiver.accept(it.toByteArray()) }
    }

    @Test fun standardCrcAndIndependentCompleteWireFixture() {
        assertEquals(0x29b1, BleTextFrameDecoder.crc16("123456789".toByteArray()))
        // CRC calculated separately by polynomial long division, not this decoder / test encoder.
        val fixture = "a5 5a 01 01 04 03 02 01 07 00 65 6e 6f 70 65 6e 20 51 51 85 19 0d 0a"
            .split(' ').map { it.toInt(16).toByte() }.toByteArray()
        val r = receiver()
        feed(r, fixture)
        assertEquals(listOf(BleTextFrame(1, 0x01020304, "en", "open QQ")), frames)
        assertTrue(errors.toString(), errors.isEmpty())
        assertFalse(r.hasPendingFrame)
    }

    @Test fun allPossibleTwoPartSplitsWaitForLastByte() {
        val bytes = frame("open Café Radio")
        for (split in 1 until bytes.size) {
            frames.clear()
            val r = receiver()
            r.accept(bytes.copyOfRange(0, split))
            assertTrue(frames.isEmpty()); assertTrue(r.hasPendingFrame)
            r.accept(bytes.copyOfRange(split, bytes.size))
            assertEquals("open Café Radio", frames.single().text)
            assertFalse(r.hasPendingFrame)
        }
        assertTrue(errors.toString(), errors.isEmpty())
    }

    @Test fun oneByteNotificationsHandleUtf8ByteLengthAndUnsignedSequence() {
        val r = receiver()
        // UTF-8 transport works; the protocol still requires the literal en language field.
        feed(r, frame("打开 QQ 🎧", seq = 0xffffffffL) + frame("", seq = 0), 1)
        assertEquals(listOf("打开 QQ 🎧", ""), frames.map { it.text })
        assertEquals(listOf(0xffffffffL, 0L), frames.map { it.sequence })
        assertTrue(errors.isEmpty()); assertFalse(r.hasPendingFrame)
    }

    @Test fun maximumBodyAndMultipleFramesInOneNotification() {
        val r = receiver()
        r.accept(frame("x".repeat(1024)) + frame("busy", seq = 2, type = 2) + frame("open QQ", seq = 3))
        assertEquals(listOf(1024, 4, 7), frames.map { it.text.length })
        assertEquals("--", frames[1].language)
        assertTrue(errors.isEmpty()); assertFalse(r.hasPendingFrame)
    }

    @Test fun crcTailAndOversizedLengthRejectThenResynchronize() {
        val badCrc = frame().also { it[12] = 'X'.code.toByte() }
        val badTail = frame().also { it[it.lastIndex] = 0 }
        val oversized = frame().copyOf(12).also { it[8] = 1; it[9] = 4 }
        val r = receiver()
        feed(r, byteArrayOf(0xa5.toByte()) + badCrc + badTail + oversized + frame(seq = 5), 1)
        assertEquals(5L, frames.single().sequence)
        assertEquals(3, errors.size)
        assertFalse(r.hasPendingFrame)
    }

    @Test fun corruptBodyCannotEscapeIntoLegacyJsonOrRunCommands() {
        val r = receiver()
        val malicious = frame("{\"v\":1,\"type\":\"command\",\"text\":\"call Alice\"}")
            .also { it[it.size - 4] = (it[it.size - 4].toInt() xor 1).toByte() }
        feed(r, malicious + frame("recovered", seq = 3, type = 2))
        assertEquals(listOf(2), frames.map { it.type })
        assertEquals(1, errors.size)
    }

    @Test fun unsupportedVersionTypeLanguageInvalidUtf8AndNulNeverDelivered() {
        val invalid = listOf(frame(version = 2), frame(type = 3), frame(lang = "zh"),
            frame(type = 2, lang = "en"), frame(body = byteArrayOf(0xc3.toByte(), 0x28)), frame("open QQ\u0000"))
        val r = receiver()
        for (bytes in invalid) feed(r, bytes, 1)
        assertTrue(frames.isEmpty()); assertEquals(invalid.size, errors.size)
        feed(r, frame(seq = 9))
        assertEquals(9L, frames.single().sequence)
        assertFalse(r.hasPendingFrame)
    }

    @Test fun damagedFrameDoesNotDeduplicateCorrectResend() {
        val routed = mutableListOf<BleTextFrame>()
        val session = BleTextFrameSession({ routed.add(it) }, { fail("Unexpected status") })
        val r = receiver(session::accept)
        val bytes = frame()
        feed(r, bytes.copyOf().also { it[12] = 0 })
        feed(r, bytes)
        feed(r, bytes)
        assertEquals(1, routed.size)
        assertEquals(1, errors.size)
    }

    @Test fun dedupSequenceAcrossTypesAndKeepHistoryAcrossPartialTimeoutReset() {
        val texts = mutableListOf<Long>(); val statuses = mutableListOf<Long>(); val duplicates = mutableListOf<Long>()
        val session = BleTextFrameSession({ texts.add(it.sequence) }, { statuses.add(it.sequence) }, { duplicates.add(it.sequence) })
        val r = receiver(session::accept)
        feed(r, frame(seq = 0) + frame(seq = 0) + frame("busy", seq = 2, type = 2) + frame(seq = 2))
        r.accept(frame(seq = 3).copyOf(15)); r.reset() // partial receive timeout must not clear dedup
        feed(r, frame(seq = 0) + frame(seq = 0xffffffffL))
        assertEquals(listOf(0L, 0xffffffffL), texts)
        assertEquals(listOf(2L), statuses)
        assertEquals(listOf(0L, 2L, 0L), duplicates)
    }

    @Test fun disconnectDropsPartialAndStartsNewSequenceSession() {
        val routed = mutableListOf<Long>()
        val session = BleTextFrameSession({ routed.add(it.sequence) }, {})
        val r = receiver(session::accept)
        feed(r, frame(seq = 1))
        val bytes = frame(seq = 2)
        r.accept(bytes.copyOf(13)); assertTrue(r.hasPendingFrame)
        r.reset(); session.reset()
        assertFalse(r.hasPendingFrame)
        r.accept(bytes.copyOfRange(13, bytes.size))
        assertEquals(listOf(1L), routed)
        feed(r, frame(seq = 1))
        assertEquals(listOf(1L, 1L), routed)
    }

    @Test fun textPreemptsBButStatusAndDuplicatesDoNotStartOrConfirmB() {
        val state = AssistSession()
        val token = state.check(0)!!
        assertTrue(state.offer(token, "prompt"))
        var texts = 0
        val session = BleTextFrameSession({ texts++; state.beginA("text-${it.sequence}", 100) }, {})
        val r = receiver(session::accept)
        feed(r, frame("open QQ", seq = 10, type = 2))
        assertNull(state.aTask); assertEquals("prompt", state.promptId)
        feed(r, frame(seq = 11))
        assertEquals("text-11", state.aTask); assertNull(state.promptId); assertFalse(state.current(token))
        assertNull(state.record("prompt"))
        state.finishA("text-11", 101)
        feed(r, frame(seq = 11))
        assertNull(state.aTask); assertEquals(1, texts)
    }

    @Test fun textJsonAndWavRemainSeparateEvenWhenWavContainsACompleteTextFrame() {
        val files = mutableListOf<File>(); val json = mutableListOf<String>()
        val r = BleIncomingStream(temporary.newFolder(), { json.add(it) }, { files.add(it) }, { errors.add(it) }, onTextFrame = { frames.add(it) })
        val wav = ByteArray(108)
        ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(100); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(16000); putInt(32000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(64)
        }
        frame(seq = 99).copyInto(wav, 44)
        feed(r, frame(seq = 1) + "{\"type\":\"ping\"}".toByteArray() + wav + frame(seq = 2), 1)
        assertEquals(listOf(1L, 2L), frames.map { it.sequence })
        assertEquals(listOf("{\"type\":\"ping\"}"), json)
        assertArrayEquals(wav, files.single().readBytes())
        assertTrue(errors.toString(), errors.isEmpty())
    }

    @Test fun englishSingleCommandsResolveTargetsAndRejectQuestionsNegationOrMultipleActions() {
        assertEquals(CommandParser.Command.OpenApp("QQ"), CommandParser.parse("open QQ"))
        assertEquals(CommandParser.Command.OpenApp("Café Radio"), CommandParser.parse("Please launch Café Radio."))
        assertEquals(CommandParser.Command.Call("Alice"), CommandParser.parse("call Alice"))
        for (text in listOf("don't open QQ", "can you open QQ?", "open QQ and call Alice", "call Alice; open QQ", "open QQ\ncall Alice", "open", "call Alice or Bob"))
            assertEquals(text, CommandParser.Command.Unknown, CommandParser.parse(text))
    }
}
