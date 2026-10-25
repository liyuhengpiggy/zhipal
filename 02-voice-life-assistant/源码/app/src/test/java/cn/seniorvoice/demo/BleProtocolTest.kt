package cn.seniorvoice.demo

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Base64

class BleProtocolTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun framedPcmRebuildsStandardWavWithoutChangingSamples() {
        val transfer = BleAudioTransfer(temporary.newFolder())
        val pcm = ByteArray(32000) { (it % 251).toByte() }
        transfer.start(event("audio_start", JSONObject().put("format", "pcm_s16le").put("size", pcm.size)
            .put("sample_rate", 16000).put("channels", 1).put("bits_per_sample", 16)))
        pcm.toList().chunked(777).forEachIndexed { index, bytes ->
            transfer.append(event("audio_chunk", JSONObject().put("index", index).put("base64", Base64.getEncoder().encodeToString(bytes.toByteArray()))))
        }
        val file = transfer.finish(event("audio_end", JSONObject().put("chunks", (pcm.size + 776) / 777)))
        val info = WavPcmReader.inspect(file)
        assertEquals(16000, info.sampleRate); assertEquals(1, info.channels); assertEquals(16, info.bits)
        assertEquals(1.0, info.seconds, 0.0001)
        assertArrayEquals(pcm, file.readBytes().copyOfRange(44, file.length().toInt()))
    }

    @Test fun pcmProgressCountsPayloadAndDoesNotClaimCompletionBeforeAudioEnd() {
        val transfer = BleAudioTransfer(temporary.newFolder())
        val pcm = ByteArray(160) { it.toByte() }
        transfer.start(event("audio_start", JSONObject().put("format", "pcm_s16le").put("size", pcm.size)
            .put("sample_rate", 16000).put("channels", 1).put("bits_per_sample", 16)))
        val initial = transfer.progress(0)
        assertEquals(0L, initial.receivedBytes); assertEquals(160L, initial.totalBytes)
        assertEquals("52 49 46 46 C4 00 00 00", initial.firstBytesHex)
        transfer.append(event("audio_chunk", JSONObject().put("index", 0).put("base64", Base64.getEncoder().encodeToString(pcm.copyOfRange(0, 63)))))
        val middle = transfer.progress(100)
        assertEquals(63L, middle.receivedBytes); assertEquals(160L, middle.totalBytes)
        assertEquals(100L, middle.elapsedMs); assertFalse(middle.complete)
        transfer.append(event("audio_chunk", JSONObject().put("index", 1).put("base64", Base64.getEncoder().encodeToString(pcm.copyOfRange(63, pcm.size)))))
        val last = transfer.progress(200)
        assertEquals(160L, last.receivedBytes); assertFalse(last.complete)
        val file = transfer.finish(event("audio_end", JSONObject().put("chunks", 2)))
        assertEquals(204L, file.length())
        assertThrows(IllegalStateException::class.java) { transfer.progress(250) }
    }

    @Test fun wavProgressCollectsOnlyRiffHeaderAcrossChunksAndResetsAfterAbort() {
        val transfer = BleAudioTransfer(temporary.newFolder())
        val wav = wavBytes(100)
        transfer.start(event("audio_start", JSONObject().put("format", "wav").put("size", wav.size)))
        transfer.append(event("audio_chunk", JSONObject().put("index", 0).put("base64", Base64.getEncoder().encodeToString(wav.copyOfRange(0, 5)))))
        assertEquals("", transfer.progress(1).firstBytesHex)
        transfer.append(event("audio_chunk", JSONObject().put("index", 1).put("base64", Base64.getEncoder().encodeToString(wav.copyOfRange(5, 12)))))
        assertEquals("52 49 46 46 88 00 00 00", transfer.progress(2).firstBytesHex)
        transfer.abort()
        transfer.start(event("audio_start", JSONObject().put("format", "wav").put("size", 16)))
        transfer.append(event("audio_chunk", JSONObject().put("index", 0).put("base64", Base64.getEncoder().encodeToString("private voice!!!".toByteArray()))))
        assertEquals("", transfer.progress(3).firstBytesHex)
        transfer.abort()
    }

    @Test fun pcmWithMissingParametersOrMisalignedSizeNeverCreatesAFile() {
        val directory = temporary.newFolder(); val transfer = BleAudioTransfer(directory)
        for (data in listOf(
            JSONObject().put("format", "pcm_s16le").put("size", 32000),
            JSONObject().put("format", "pcm_s16le").put("size", 31999).put("sample_rate", 16000).put("channels", 1).put("bits_per_sample", 16),
            JSONObject().put("format", "pcm_s16le").put("size", 32000).put("sample_rate", 96000).put("channels", 1).put("bits_per_sample", 16)
        )) {
            assertThrows(IllegalArgumentException::class.java) { transfer.start(event("audio_start", data)) }
            assertFalse(transfer.isReceiving); assertEquals(0, directory.listFiles()!!.size)
        }
    }

    @Test fun partialPcmIsNotDeliveredAndAbortRemovesPartialWav() {
        val directory = temporary.newFolder(); val transfer = BleAudioTransfer(directory)
        transfer.start(event("audio_start", JSONObject().put("format", "pcm_s16le").put("size", 32000)
            .put("sample_rate", 16000).put("channels", 1).put("bits_per_sample", 16)))
        transfer.append(event("audio_chunk", JSONObject().put("index", 0).put("base64", "AAAAAA==")))
        assertThrows(IllegalArgumentException::class.java) { transfer.finish(event("audio_end", JSONObject().put("chunks", 1))) }
        transfer.abort(); assertEquals(0, directory.listFiles()!!.size)
    }

    @Test fun localSpeechRejectsCompressedAudioAtStartWithoutCreatingTransfer() {
        val transfer = BleAudioTransfer(temporary.newFolder())
        for (format in listOf("mp3", "m4a", "aac", "webm")) {
            val error = assertThrows(IllegalArgumentException::class.java) {
                transfer.start(event("audio_start", JSONObject().put("format", format).put("size", 100)))
            }
            assertTrue(error.message!!.contains("PCM WAV"))
            assertFalse(transfer.isReceiving)
        }
    }

    private fun feed(framer: BleJsonFramer, text: String, packetSize: Int = 20) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + packetSize, bytes.size)
            framer.accept(bytes.copyOfRange(offset, end))
            offset = end
        }
    }

    private fun event(type: String, data: JSONObject = JSONObject(), id: String = "rec-1") =
        DeviceEvent.parse(JSONObject().put("v", 1).put("type", type).put("id", id).put("data", data).toString())

    private fun wavBytes(audioSize: Int = 32000): ByteArray {
        val content = ByteArray(44 + audioSize)
        ByteBuffer.wrap(content).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(content.size - 8); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(16000); putInt(32000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(audioSize)
        }
        for (i in 44 until content.size) content[i] = (i % 251).toByte()
        return content
    }

    private fun feedBytes(receiver: BleIncomingStream, bytes: ByteArray, packetSize: Int = 20) {
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + packetSize, bytes.size)
            receiver.accept(bytes.copyOfRange(offset, end))
            offset = end
        }
    }

    @Test fun rawWavNotificationsProduceIdenticalFileWithoutJsonOrEndMarker() {
        val files = mutableListOf<File>()
        val errors = mutableListOf<String>()
        val receiver = BleIncomingStream(temporary.newFolder(), { fail("WAV treated as JSON") }, { files.add(it) }, { errors.add(it) })
        val content = wavBytes(160000)
        feedBytes(receiver, content)
        assertTrue(errors.toString(), errors.isEmpty())
        assertEquals(1, files.size)
        assertArrayEquals(content, files.single().readBytes())
        assertFalse(receiver.hasPendingFrame)
    }

    @Test fun rawWavHeaderCanArriveOneByteAtATimeAndPayloadIsAlwaysBinary() {
        val files = mutableListOf<File>()
        val receiver = BleIncomingStream(temporary.newFolder(), { fail("Payload treated as JSON") }, { files.add(it) }, { fail(it) })
        val content = wavBytes(512)
        val embedded = "RIFF1234WAVE{\"v\":1,\"type\":\"command\",\"id\":\"false\",\"text\":\"打开抖音\"}\n".toByteArray()
        embedded.copyInto(content, 44)
        feedBytes(receiver, content, 1)
        assertArrayEquals(content, files.single().readBytes())
    }

    @Test fun wavProgressCountsFileBytesAcrossTinyPacketsAndFinishesBeforeDelivery() {
        val content = wavBytes(100)
        val progress = mutableListOf<Triple<Long, Long, Boolean>>()
        val order = mutableListOf<String>()
        val receiver = BleIncomingStream(temporary.newFolder(), { fail(it) }, { order.add("file") }, { fail(it) },
            onWavProgress = { received, total, complete ->
                progress.add(Triple(received, total, complete))
                if (complete) order.add("complete")
            })
        feedBytes(receiver, content.copyOfRange(0, 12), 1)
        assertEquals(listOf(Triple(12L, content.size.toLong(), false)), progress)
        assertEquals("52 49 46 46 88 00 00 00", receiver.wavFirstBytesHex)
        receiver.accept(content.copyOfRange(12, 51))
        assertEquals(Triple(51L, content.size.toLong(), false), progress.last())
        receiver.accept(content.copyOfRange(51, content.size))
        assertEquals(Triple(content.size.toLong(), content.size.toLong(), true), progress.last())
        assertEquals(listOf("complete", "file"), order)
        assertTrue(progress.zipWithNext().all { (before, after) -> after.first > before.first })
    }

    @Test fun corruptOrAbortedWavNeverReportsSuccessfulCompletion() {
        val progress = mutableListOf<Triple<Long, Long, Boolean>>()
        val errors = mutableListOf<String>()
        val receiver = BleIncomingStream(temporary.newFolder(), { fail(it) }, { fail("Invalid WAV delivered") }, { errors.add(it) },
            onWavProgress = { received, total, complete -> progress.add(Triple(received, total, complete)) })
        val broken = wavBytes(100)
        ByteBuffer.wrap(broken).order(ByteOrder.LITTLE_ENDIAN).putInt(40, broken.size)
        feedBytes(receiver, broken, 20)
        assertEquals(1, errors.size)
        assertFalse(progress.any { it.third })
        assertEquals("", receiver.wavFirstBytesHex)
        receiver.accept(wavBytes().copyOfRange(0, 100))
        receiver.reset()
        assertEquals("", receiver.wavFirstBytesHex)
        assertFalse(receiver.hasPendingFrame)
        assertFalse(progress.any { it.third })
    }

    @Test fun adjacentWavsHaveIndependentLengthsAndProgressCompletion() {
        val first = wavBytes(100)
        val second = wavBytes(200)
        val progress = mutableListOf<Triple<Long, Long, Boolean>>()
        val receiver = BleIncomingStream(temporary.newFolder(), { fail(it) }, {}, { fail(it) },
            onWavProgress = { received, total, complete -> progress.add(Triple(received, total, complete)) })
        // A BLE notification boundary is unrelated to a file boundary.
        receiver.accept(first + second)
        assertEquals(listOf(
            Triple(12L, first.size.toLong(), false), Triple(first.size.toLong(), first.size.toLong(), true),
            Triple(12L, second.size.toLong(), false), Triple(second.size.toLong(), second.size.toLong(), true)
        ), progress)
    }

    @Test fun truncatedRawWavIsNotDeliveredAndResetDeletesItsFile() {
        val directory = temporary.newFolder()
        val receiver = BleIncomingStream(directory, { fail(it) }, { fail("Incomplete recording delivered") }, { fail(it) })
        receiver.accept(wavBytes().copyOfRange(0, 1000))
        assertTrue(receiver.hasPendingFrame)
        assertEquals(1, directory.listFiles()!!.size)
        receiver.reset()
        assertFalse(receiver.hasPendingFrame)
        assertEquals(0, directory.listFiles()!!.size)
    }

    @Test fun rawWavAndLegacyJsonCanShareTheStreamWithoutNewlines() {
        val files = mutableListOf<File>()
        val events = mutableListOf<DeviceEvent>()
        val receiver = BleIncomingStream(temporary.newFolder(), { events.add(DeviceEvent.parse(it)) }, { files.add(it) }, { fail(it) })
        val json = "{\"v\":1,\"type\":\"ping\",\"id\":\"ping-1\"}".toByteArray()
        val first = wavBytes(100)
        val second = wavBytes(200)
        receiver.accept(json + first + second + json)
        assertEquals(2, events.size)
        assertEquals(2, files.size)
        assertArrayEquals(first, files[0].readBytes())
        assertArrayEquals(second, files[1].readBytes())
    }

    @Test fun invalidRawWavSizesAreRejectedBeforeFileCreationAndNextWavWorks() {
        val directory = temporary.newFolder()
        val files = mutableListOf<File>()
        val errors = mutableListOf<String>()
        val receiver = BleIncomingStream(directory, { fail(it) }, { files.add(it) }, { errors.add(it) })
        for (size in listOf(0, -1, BleAudioTransfer.MAX_AUDIO_BYTES)) {
            val header = wavBytes().copyOfRange(0, 12)
            ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).putInt(4, size)
            feedBytes(receiver, header, 1)
            assertEquals(0, directory.listFiles()!!.size)
        }
        assertEquals(3, errors.size)
        receiver.accept(wavBytes())
        assertEquals(1, files.size)
    }

    @Test fun inconsistentWavChunkSizesAreRejectedAndCleanedUp() {
        val directory = temporary.newFolder()
        val errors = mutableListOf<String>()
        val receiver = BleIncomingStream(directory, { fail(it) }, { fail("Corrupt WAV delivered") }, { errors.add(it) })
        val broken = wavBytes()
        ByteBuffer.wrap(broken).order(ByteOrder.LITTLE_ENDIAN).putInt(40, broken.size)
        receiver.accept(broken)
        assertEquals(1, errors.size)
        assertEquals(0, directory.listFiles()!!.size)
        assertFalse(receiver.hasPendingFrame)
    }

    @Test fun wavWithExtraMetadataAndOddChunkPaddingIsAccepted() {
        val files = mutableListOf<File>()
        val receiver = BleIncomingStream(temporary.newFolder(), { fail(it) }, { files.add(it) }, { fail(it) })
        val original = wavBytes(100)
        val metadata = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .put("JUNK".toByteArray()).putInt(3).put(byteArrayOf(1, 2, 3, 0)).array()
        val extended = original.copyOfRange(0, 12) + metadata + original.copyOfRange(12, original.size)
        ByteBuffer.wrap(extended).order(ByteOrder.LITTLE_ENDIAN).putInt(4, extended.size - 8)
        feedBytes(receiver, extended, 7)
        assertArrayEquals(extended, files.single().readBytes())
    }

    @Test fun rawWavAtTenMiBLimitIsAccepted() {
        val files = mutableListOf<File>()
        val receiver = BleIncomingStream(temporary.newFolder(), { fail(it) }, { files.add(it) }, { fail(it) })
        val content = wavBytes(BleAudioTransfer.MAX_AUDIO_BYTES - 44)
        feedBytes(receiver, content, 512)
        assertEquals(BleAudioTransfer.MAX_AUDIO_BYTES.toLong(), files.single().length())
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(content),
            MessageDigest.getInstance("SHA-256").digest(files.single().readBytes()))
    }

    @Test fun messagesLargerThanEightKiBAreDeliveredAcrossNotifications() {
        val directory = temporary.newFolder()
        val messages = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val framer = BleJsonFramer(directory, { messages.add(it) }, { errors.add(it) })
        val message = JSONObject().put("v", 1).put("id", "large-1").put("type", "audio_chunk")
            .put("data", JSONObject().put("index", 0).put("base64", "A".repeat(100000))).toString()
        feed(framer, message + "\n")
        assertEquals(listOf(message), messages)
        assertTrue(errors.toString(), errors.isEmpty())
        assertEquals(0, directory.listFiles()!!.size)
    }

    @Test fun utf8QuotesAndAdjacentObjectsWorkWithoutNewlines() {
        val messages = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val framer = BleJsonFramer(temporary.newFolder(), { messages.add(it) }, { errors.add(it) })
        val first = JSONObject().put("text", "给女儿打电话，字符串里的 { 与 } 和引号 \" 不结束消息").toString()
        val second = "{\"v\":1,\"id\":\"p1\",\"type\":\"ping\"}"
        feed(framer, first + second + "\r\n", 1)
        assertEquals(listOf(first, second), messages)
        assertTrue(errors.isEmpty())
    }

    @Test fun fourMiBFrameIsAcceptedButOneExtraByteIsRejected() {
        val directory = temporary.newFolder()
        val messages = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val framer = BleJsonFramer(directory, { messages.add(it) }, { errors.add(it) })
        val overhead = "{\"data\":\"\"}".length
        val exact = "{\"data\":\"" + "A".repeat(BleJsonFramer.MAX_FRAME_BYTES - overhead) + "\"}"
        feed(framer, exact, 512)
        assertEquals(listOf(exact), messages)
        feed(framer, exact.dropLast(2) + "A\"}\n", 512)
        feed(framer, "{\"recovered\":true}")
        assertEquals(1, errors.size)
        assertEquals(2, messages.size)
        assertEquals("{\"recovered\":true}", messages.last())
        assertEquals(0, directory.listFiles()!!.size)
    }

    @Test fun oversizedFrameIsDiscardedAndNextLineRecovers() {
        val directory = temporary.newFolder()
        val messages = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val framer = BleJsonFramer(directory, { messages.add(it) }, { errors.add(it) }, maxFrameBytes = 128, memoryThreshold = 16)
        feed(framer, "{\"data\":\"" + "x".repeat(300) + "\"}\n{\"ok\":true}\n")
        assertEquals(listOf("{\"ok\":true}"), messages)
        assertEquals(1, errors.size)
        assertEquals(0, directory.listFiles()!!.size)
    }

    @Test fun resetRemovesPartialSpoolBeforeNewConnection() {
        val directory = temporary.newFolder()
        val messages = mutableListOf<String>()
        val framer = BleJsonFramer(directory, { messages.add(it) }, { fail(it) }, memoryThreshold = 16)
        feed(framer, "{\"data\":\"" + "x".repeat(100))
        assertEquals(1, directory.listFiles()!!.size)
        framer.reset()
        assertEquals(0, directory.listFiles()!!.size)
        feed(framer, "{\"new\":1}")
        assertEquals(listOf("{\"new\":1}"), messages)
    }

    @Test fun invalidUtf8DoesNotReachMessageHandler() {
        val errors = mutableListOf<String>()
        val framer = BleJsonFramer(temporary.newFolder(), { fail("Invalid UTF-8 was delivered") }, { errors.add(it) })
        framer.accept("{\"data\":\"".toByteArray() + byteArrayOf(0xc3.toByte(), 0x28) + "\"}".toByteArray())
        assertEquals(1, errors.size)
    }

    @Test fun bareBinaryIsRejectedAndNextJsonRecovers() {
        val messages = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val framer = BleJsonFramer(temporary.newFolder(), { messages.add(it) }, { errors.add(it) })
        framer.accept(byteArrayOf(0, 1, 2, 3, 10))
        feed(framer, "{\"ok\":true}")
        assertEquals(1, errors.size)
        assertEquals(1, messages.size)
    }

    @Test fun fiveSecondWavPassesFramingParsingAndFileAssembly() {
        val content = ByteArray(44 + 16000 * 2 * 5)
        ByteBuffer.wrap(content).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(content.size - 8); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(16000); putInt(32000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(content.size - 44)
        }
        for (i in 44 until content.size) content[i] = (i % 127).toByte()
        val directory = temporary.newFolder()
        val transfer = BleAudioTransfer(directory)
        var completed: File? = null
        val framer = BleJsonFramer(directory, { line ->
            val parsed = DeviceEvent.parse(line)
            when (parsed.type) {
                "audio_start" -> transfer.start(parsed)
                "audio_chunk" -> transfer.append(parsed)
                "audio_end" -> completed = transfer.finish(parsed)
            }
        }, { fail(it) })
        fun send(type: String, data: JSONObject) {
            val message = JSONObject().put("v", 1).put("type", type).put("id", "wav-1").put("data", data).toString()
            feed(framer, message, 185)
        }
        send("audio_start", JSONObject().put("format", "wav").put("size", content.size))
        var index = 0
        var offset = 0
        while (offset < content.size) {
            val end = minOf(offset + 65536, content.size)
            send("audio_chunk", JSONObject().put("index", index++).put("base64", Base64.getEncoder().encodeToString(content.copyOfRange(offset, end))))
            offset = end
        }
        send("audio_end", JSONObject().put("chunks", index))
        assertArrayEquals(content, checkNotNull(completed).readBytes())
        assertFalse(transfer.isReceiving)
        assertEquals(1, directory.listFiles()!!.size)
    }

    @Test fun tenMiBAudioIsStoredWithoutTruncation() {
        val directory = temporary.newFolder()
        val transfer = BleAudioTransfer(directory)
        val block = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
        val encoded = Base64.getEncoder().encodeToString(block)
        transfer.start(event("audio_start", JSONObject().put("format", "wav").put("size", 10 * 1024 * 1024)))
        val expectedHash = MessageDigest.getInstance("SHA-256")
        repeat(5) { index ->
            transfer.append(event("audio_chunk", JSONObject().put("index", index).put("base64", encoded)))
            expectedHash.update(block)
        }
        val result = transfer.finish(event("audio_end", JSONObject().put("chunks", 5)))
        val actualHash = MessageDigest.getInstance("SHA-256")
        result.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val n = input.read(buffer); if (n < 0) break; actualHash.update(buffer, 0, n) }
        }
        assertEquals(10L * 1024 * 1024, result.length())
        assertArrayEquals(expectedHash.digest(), actualHash.digest())
    }

    @Test fun outOfOrderAudioCannotProduceACompletedRecording() {
        val directory = temporary.newFolder()
        val transfer = BleAudioTransfer(directory)
        transfer.start(event("audio_start", JSONObject().put("format", "wav").put("size", 3)))
        assertThrows(IllegalArgumentException::class.java) {
            transfer.append(event("audio_chunk", JSONObject().put("index", 1).put("base64", "AQID")))
        }
        transfer.abort()
        assertEquals(0, directory.listFiles()!!.size)
    }

    @Test fun missingBytesAndInvalidBase64AreRejected() {
        val transfer = BleAudioTransfer(temporary.newFolder())
        transfer.start(event("audio_start", JSONObject().put("format", "wav").put("size", 4)))
        assertThrows(IllegalArgumentException::class.java) {
            transfer.append(event("audio_chunk", JSONObject().put("index", 0).put("base64", "@@@!")))
        }
        transfer.append(event("audio_chunk", JSONObject().put("index", 0).put("base64", "AQID")))
        assertThrows(IllegalArgumentException::class.java) { transfer.finish(event("audio_end", JSONObject().put("chunks", 1))) }
        transfer.abort()
    }
}
