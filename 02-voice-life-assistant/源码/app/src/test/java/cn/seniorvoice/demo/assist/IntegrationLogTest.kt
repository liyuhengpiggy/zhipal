package cn.seniorvoice.demo.assist

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import cn.seniorvoice.demo.nextaction.observation.InteractionHistory

class IntegrationLogTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun logDropsCredentialsQueriesUserTextAndUnknownIds() {
        IntegrationLog.initialize(temporary.newFolder())
        IntegrationLog.event("HTTP", "request", mapOf("task_id" to "v_123", "token" to "secret-token", "text" to "private transcript"),
            url = "https://user:private-pass@example.test/signed-secret?token=private-query")
        val value = IntegrationLog.read()
        assertTrue(value.contains("v_123")); assertTrue(value.contains("https://example.test"))
        for (secret in listOf("secret-token", "private transcript", "private-pass", "signed-secret", "private-query")) assertFalse(value.contains(secret))
    }
    @Test fun historyExpiresAtSixtySecondsAndDeduplicatesWithoutLabels() {
        val history = InteractionHistory()
        history.add(0, "CLICK", "example.app", 1); history.add(100, "CLICK", "example.app", 1)
        history.add(1000, "SCROLL", "example.app", 1)
        assertEquals(2, history.snapshot(50000).size)
        assertEquals(1, history.snapshot(60001).size)
        assertEquals(0, history.snapshot(61001).size)
    }

    @Test fun transferMetricsKeepTimingAndProtocolMetadataWithoutAudioSamples() {
        IntegrationLog.initialize(temporary.newFolder())
        IntegrationLog.event("BLE_RX", "audio_received", mapOf("task_id" to "a-123", "button" to "A"),
            bytes = 32044, elapsedMs = 2000, bytesPerSecond = 16022, firstBytesHex = "52 49 46 46 24 7D 00 00")
        val row = org.json.JSONObject(IntegrationLog.read().trim())
        assertEquals(32044L, row.getLong("bytes")); assertEquals(2000L, row.getLong("elapsed_ms"))
        assertEquals(16022L, row.getLong("bytes_per_second"))
        assertEquals("52 49 46 46 24 7D 00 00", row.getString("first_8_bytes_hex"))
        assertEquals("a-123", row.getString("task_id"))
    }

    @Test fun negativeMetricsAndArbitraryPayloadPrefixAreNotLogged() {
        IntegrationLog.initialize(temporary.newFolder())
        IntegrationLog.event("BLE_RX", "invalid", bytes = -1, elapsedMs = -1, bytesPerSecond = -1,
            firstBytesHex = "70 72 69 76 61 74 65 21")
        val row = org.json.JSONObject(IntegrationLog.read().trim())
        assertFalse(row.has("bytes")); assertFalse(row.has("elapsed_ms")); assertFalse(row.has("bytes_per_second"))
        assertFalse(row.has("first_8_bytes_hex"))
    }
}
