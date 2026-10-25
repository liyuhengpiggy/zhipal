package cn.seniorvoice.demo

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioRecordingFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun retainKeepsOriginalAvailableForAsrAndReplacesPreviousRetryFile() {
        val directory = temporary.newFolder()
        val first = recording("first.wav", 160)
        val retained = AudioRecordingFiles.retain(first, directory)
        assertArrayEquals(first.readBytes(), retained.readBytes())
        assertTrue(first.isFile)
        val second = recording("second.wav", 320)
        assertEquals(retained, AudioRecordingFiles.retain(second, directory))
        assertArrayEquals(second.readBytes(), retained.readBytes())
        assertTrue(second.isFile)
        assertEquals(listOf("recording.wav"), directory.list()!!.toList())
    }

    @Test fun invalidIncomingAudioCannotDestroyLastValidRetryRecording() {
        val directory = temporary.newFolder()
        val source = recording("valid.wav", 160)
        val retained = AudioRecordingFiles.retain(source, directory)
        val original = retained.readBytes()
        val invalid = temporary.newFile("truncated.wav").apply { writeBytes(original.copyOfRange(0, 50)) }
        assertThrows(IllegalArgumentException::class.java) { AudioRecordingFiles.retain(invalid, directory) }
        assertArrayEquals(original, retained.readBytes())
        assertEquals(listOf("recording.wav"), directory.list()!!.toList())
    }

    private fun recording(name: String, audioBytes: Int): File {
        val bytes = ByteBuffer.allocate(audioBytes + 44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(audioBytes + 36); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(16000); putInt(32000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(audioBytes)
            repeat(audioBytes) { put((it % 127).toByte()) }
        }.array()
        return temporary.newFile(name).apply { writeBytes(bytes) }
    }
}
