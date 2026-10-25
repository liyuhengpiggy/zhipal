package cn.seniorvoice.demo

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

class WavPcmReaderTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun chunk(tag: String, bytes: ByteArray): ByteArray =
        ByteBuffer.allocate(8 + bytes.size + bytes.size % 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(tag.toByteArray(Charsets.US_ASCII)); putInt(bytes.size); put(bytes)
        }.array()

    private fun wav(samples: IntArray, rate: Int = 16000, channels: Int = 1, bits: Int = 16,
                    encoding: Int = 1, metadata: Boolean = false, trailingMetadata: Boolean = false): File {
        val pcm = ByteArrayOutputStream()
        samples.forEach { value ->
            val raw = if (bits == 8) (value shr 8) + 128 else value.toLong() shl (bits - 16)
            for (byte in 0 until bits / 8) pcm.write((raw.toLong() shr (byte * 8)).toInt() and 255)
        }
        val format = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(encoding.toShort()); putShort(channels.toShort()); putInt(rate)
            putInt(rate * channels * bits / 8); putShort((channels * bits / 8).toShort()); putShort(bits.toShort())
        }.array()
        val extra = if (metadata) chunk("JUNK", byteArrayOf(1, 2, 3)) else byteArrayOf()
        val tail = if (trailingMetadata) chunk("LIST", ByteArray(1000) { 127 }) else byteArrayOf()
        val body = "WAVE".toByteArray() + extra + chunk("fmt ", format) + chunk("data", pcm.toByteArray()) + tail
        return temporary.newFile().apply {
            writeBytes(ByteBuffer.allocate(8 + body.size).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt(body.size); put(body)
            }.array())
        }
    }

    private fun read(file: File): IntArray {
        val output = ByteArrayOutputStream()
        WavPcmReader.stream16k(file) { bytes, count ->
            assertTrue(count <= 4096 && count % 2 == 0)
            output.write(bytes, 0, count)
        }
        val bytes = ByteBuffer.wrap(output.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
        return IntArray(bytes.remaining() / 2) { bytes.short.toInt() }
    }

    @Test fun native16kPreservesSignedSamplesIncludingExtremes() {
        val samples = intArrayOf(-32768, -20000, -1, 0, 1, 16384, 32767)
        val file = wav(samples)
        val info = WavPcmReader.inspect(file)
        assertEquals(44, info.dataOffset.toInt()); assertEquals(7, info.frames.toInt())
        assertArrayEquals(samples, read(file))
    }

    @Test fun native16kStreamsExactBytesAcrossBlocksAndExcludesTrailingMetadata() {
        val samples = IntArray(16000 + 7) { ((it * 7919) and 65535) - 32768 }
        val file = wav(samples, metadata = true, trailingMetadata = true)
        val progress = mutableListOf<Int>()
        var blocks = 0
        WavPcmReader.stream16k(file, onProgress = progress::add) { _, count ->
            assertTrue(count in 2..4096 && count % 2 == 0)
            blocks++
        }
        assertEquals((samples.size * 2 + 4095) / 4096, blocks)
        assertArrayEquals(samples, read(file))
        assertEquals(0, progress.first())
        assertEquals(100, progress.last())
        assertTrue(progress.zipWithNext().all { (before, after) -> before <= after })
    }

    @Test fun conversionAcrossInputBlocksMatchesSamplePositionsForAllPcmWidths() {
        for (rate in listOf(8000, 16000, 22050, 44100, 48000)) {
            for (channels in 1..2) for (bits in listOf(8, 16, 24, 32)) {
                // Multiples of 256 are exactly representable even by unsigned 8-bit PCM.
                val samples = IntArray(5003 * channels) { ((it * 37) % 256 - 128) * 256 }
                val mono = IntArray(5003) { index ->
                    (0 until channels).sumOf { samples[index * channels + it] } / channels
                }
                val expected = IntArray(((mono.size.toLong() * 16000 + rate - 1) / rate).toInt()) { index ->
                    val position = index * rate.toDouble() / 16000
                    val left = position.toInt().coerceAtMost(mono.lastIndex)
                    val right = (left + 1).coerceAtMost(mono.lastIndex)
                    (mono[left] + (mono[right] - mono[left]) * (position - left)).roundToInt()
                }
                assertArrayEquals("$rate Hz / $channels channels / $bits bit", expected,
                    read(wav(samples, rate = rate, channels = channels, bits = bits, trailingMetadata = true)))
            }
        }
    }

    @Test fun threadCancellationStopsBothNativeAndConvertedStreamsBetweenBlocks() {
        for (rate in listOf(16000, 8000)) {
            var callbacks = 0
            try {
                assertThrows(IllegalStateException::class.java) {
                    WavPcmReader.stream16k(wav(IntArray(rate) { 1000 }, rate = rate)) { _, _ ->
                        callbacks++
                        Thread.currentThread().interrupt()
                    }
                }
                assertEquals(1, callbacks)
            } finally {
                // JUnit reuses this thread; do not leak the cancellation into other tests.
                Thread.interrupted()
            }
        }
    }

    @Test fun metadataAndOddChunkPaddingDoNotBecomeSpeech() {
        val samples = intArrayOf(0, 200, -200, 32767)
        val file = wav(samples, metadata = true)
        assertEquals(56, WavPcmReader.inspect(file).dataOffset.toInt())
        assertArrayEquals(samples, read(file))
    }

    @Test fun stereoIsAveragedBeforeRecognition() {
        val file = wav(intArrayOf(1000, -1000, 3000, 1000, -32768, -32768), channels = 2)
        assertArrayEquals(intArrayOf(0, 2000, -32768), read(file))
    }

    @Test fun integer8BitUsesUnsignedMidpointAndHonorsOddPadding() {
        assertArrayEquals(intArrayOf(-32768, 0, 32512), read(wav(intArrayOf(-32768, 0, 32512), bits = 8)))
    }

    @Test fun integer24And32BitPreserveSignAndScale() {
        val samples = intArrayOf(-32768, -256, -1, 0, 1, 32767)
        for (bits in listOf(24, 32)) assertArrayEquals(samples, read(wav(samples, bits = bits)))
    }

    @Test fun upsamplingInterpolatesAndEndsAtLastFrame() {
        assertArrayEquals(intArrayOf(0, 500, 1000, 1000), read(wav(intArrayOf(0, 1000), rate = 8000)))
    }

    @Test fun commonDownsamplingRatesProduce16kAndPreserveConstantSignal() {
        for (rate in listOf(44100, 48000)) {
            val converted = read(wav(IntArray(rate) { 1000 }, rate = rate))
            assertEquals(16000, converted.size)
            assertTrue(converted.all { it == 1000 })
        }
    }

    @Test fun fractionalEndFrameHasBoundedOutputAndProgressCompletes() {
        val file = wav(intArrayOf(0, 1000, 2000, 3000), rate = 44100)
        assertEquals(2, read(file).size)
        val progress = mutableListOf<Int>()
        WavPcmReader.stream16k(file, onProgress = progress::add) { _, _ -> }
        assertEquals(100, progress.last())
    }

    @Test fun floatAndCompressedWavAreRejectedWithActionableError() {
        for (encoding in listOf(3, 6, 7)) {
            val error = assertThrows(IllegalArgumentException::class.java) {
                WavPcmReader.inspect(wav(intArrayOf(0, 1), encoding = encoding))
            }
            assertTrue(error.message!!.contains("整数 PCM"))
        }
    }

    @Test fun unsupportedRatesChannelsAndEmptyDataAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { WavPcmReader.inspect(wav(intArrayOf(0), rate = 96000)) }
        assertThrows(IllegalArgumentException::class.java) { WavPcmReader.inspect(wav(intArrayOf(0, 0, 0), channels = 3)) }
        assertThrows(IllegalArgumentException::class.java) { WavPcmReader.inspect(wav(intArrayOf())) }
    }

    @Test fun truncatedAndForgedRiffLengthsAreRejected() {
        val file = wav(intArrayOf(0, 10, 20))
        val original = file.readBytes()
        file.writeBytes(original.copyOf(original.size - 1))
        assertThrows(IllegalArgumentException::class.java) { WavPcmReader.inspect(file) }
        file.writeBytes(original.apply { this[4] = 0 })
        assertThrows(IllegalArgumentException::class.java) { WavPcmReader.inspect(file) }
    }

    @Test fun misalignedStereoFramesAndInconsistentByteRateAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            WavPcmReader.inspect(wav(intArrayOf(0, 0, 0), channels = 2))
        }
        val file = wav(intArrayOf(0, 10))
        file.writeBytes(file.readBytes().apply { this[28] = 0; this[29] = 0 })
        assertThrows(IllegalArgumentException::class.java) { WavPcmReader.inspect(file) }
    }

    @Test fun rejectsHiddenTrailingBytesAndOutOfBoundsChunks() {
        val file = wav(intArrayOf(0, 1))
        val original = file.readBytes()
        val tail = original + byteArrayOf(0, 0)
        ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN).putInt(4, tail.size - 8)
        file.writeBytes(tail)
        assertThrows(IllegalArgumentException::class.java) { WavPcmReader.inspect(file) }
        ByteBuffer.wrap(original).order(ByteOrder.LITTLE_ENDIAN).putInt(40, 100000)
        file.writeBytes(original)
        assertThrows(IllegalArgumentException::class.java) { WavPcmReader.inspect(file) }
    }

    @Test fun durationLimitProtectsPhoneFromUnboundedInference() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            WavPcmReader.inspect(wav(IntArray(8000 * 181), rate = 8000))
        }
        assertTrue(error.message!!.contains("180 秒"))
    }
}
