package cn.seniorvoice.demo.assist

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class ACommandApiClientTest {
    private val request = ACommandRequest("a_http", "回到桌面", emptyList(), emptyList())
    private val success = """{"version":1,"task_id":"a_http","intent":{"type":"navigate","target":"home"}}"""
    private val serverToken = "own-cloud-opaque-test-token"

    @Test fun actualHttpPostsTheExactTaskToTheNormalizedEndpointAndParsesOneIntent() {
        Fixture { incoming ->
            assertTrue(incoming.line.startsWith("POST /v1/commands/interpret "))
            assertEquals("Bearer $serverToken", incoming.headers["authorization"])
            assertEquals("application/json", incoming.headers["accept"])
            val json = JSONObject(incoming.body.toString(Charsets.UTF_8))
            assertEquals("a_http", json.getString("task_id")); assertEquals(request.utterance, json.getString("utterance"))
            assertTrue(json.isNull("screen")); assertEquals(0, json.getJSONArray("apps").length())
            Reply(body = success.toByteArray())
        }.use { server ->
            ACommandApiClient(server.base + "/v1/chat/completions", serverToken, true, true).use { client ->
                assertEquals(ACommandIntent.Navigate(ACommandNavigation.HOME), client.interpret(request).intent)
            }
            assertEquals(1, server.calls.get())
        }
    }

    @Test fun redirectsAndHttpFailuresNeverForwardCredentialsOrExposeResponseBodies() {
        for (code in listOf(302, 401, 403, 404, 429, 500, 503)) Fixture {
            Reply(code, "provider-secret-body $serverToken".toByteArray(), mapOf("Location" to "/stolen"))
        }.use { server ->
            ACommandApiClient(server.base, serverToken, true, true).use { client ->
                val error = assertThrows(ACommandHttpException::class.java) { client.interpret(request) }
                assertEquals(code, error.statusCode); assertEquals("A 指令服务 HTTP $code", error.message)
                assertFalse(error.toString().contains(serverToken)); assertFalse(error.toString().contains("provider-secret-body"))
            }
            assertEquals(1, server.calls.get())
        }
    }

    @Test fun boundedBodiesInvalidUtf8AndUnexpectedContentTypesAreRejected() {
        val replies = listOf(
            Reply(body = ByteArray(ACommandProtocol.MAX_RESPONSE_BYTES + 1) { 32 }),
            Reply(body = "x".toByteArray(), extraHeaders = mapOf("Content-Length" to (ACommandProtocol.MAX_RESPONSE_BYTES + 1).toString())),
            Reply(body = byteArrayOf(0xc3.toByte(), 0x28)),
            Reply(body = success.toByteArray(), extraHeaders = mapOf("Content-Type" to "text/event-stream")),
            Reply(body = success.replace("a_http", "old_task").toByteArray()),
            Reply(body = success.dropLast(1).toByteArray())
        )
        replies.forEach { reply -> Fixture(ignoreDisconnect = true) { reply }.use { server ->
            ACommandApiClient(server.base, serverToken, true, true).use { client ->
                val error = assertThrows(IllegalArgumentException::class.java) { client.interpret(request) }
                assertFalse(error.toString().contains(serverToken)); assertFalse(error.toString().contains(success))
            }
        } }
    }

    @Test fun cancellingAnInFlightCallClosesItAndInvalidatesQueuedTickets() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        Fixture(ignoreDisconnect = true) { entered.countDown(); release.await(4, TimeUnit.SECONDS); Reply(body = success.toByteArray()) }.use { server ->
            ACommandApiClient(server.base, serverToken, true, true).use { client ->
                val ticket = client.ticket(); val done = CountDownLatch(1); val failure = AtomicReference<Throwable?>()
                thread(isDaemon = true) {
                    try { client.interpret(request, ticket) } catch (error: Throwable) { failure.set(error) } finally { done.countDown() }
                }
                try {
                    assertTrue(entered.await(3, TimeUnit.SECONDS)); client.cancelAll()
                    assertTrue(done.await(2, TimeUnit.SECONDS))
                    assertTrue(failure.get() is IllegalStateException)
                    assertThrows(IllegalStateException::class.java) { client.interpret(request, ticket) }
                    assertEquals(1, server.calls.get())
                } finally { release.countDown() }
            }
        }
    }

    @Test fun freshTicketCanBeUsedAfterCancellationButOldTicketCannotStartHttp() {
        Fixture { Reply(body = success.toByteArray()) }.use { server ->
            ACommandApiClient(server.base, serverToken, true, true).use { client ->
                val old = client.ticket(); client.cancel()
                assertThrows(IllegalStateException::class.java) { client.interpret(request, old) }
                assertEquals(0, server.calls.get())
                assertEquals("a_http", client.interpret(request, client.ticket()).taskId)
            }
        }
    }

    @Test fun connectionErrorsDoNotIncludeTheEndpointTokenOrLowLevelException() {
        val port = ServerSocket(0).use { it.localPort }
        ACommandApiClient("http://127.0.0.1:$port", serverToken, true, true).use { client ->
            val failure = assertThrows(IOException::class.java) { client.interpret(request) }
            assertEquals("A 指令网络请求失败或超时，请检查服务器配置后重试", failure.message)
            assertFalse(failure.toString().contains(serverToken)); assertNull(failure.cause)
        }
    }

    private data class Incoming(val line: String, val headers: Map<String, String>, val body: ByteArray)
    private data class Reply(val code: Int = 200, val body: ByteArray, val extraHeaders: Map<String, String> = emptyMap())
    private class Fixture(private val ignoreDisconnect: Boolean = false, private val handle: (Incoming) -> Reply) : AutoCloseable {
        private val server = ServerSocket(0)
        val base = "http://127.0.0.1:${server.localPort}"
        val calls = AtomicInteger()
        private val failure = AtomicReference<Throwable?>()
        private val runner = thread(isDaemon = true) {
            try {
                while (!server.isClosed) server.accept().use { socket ->
                    socket.soTimeout = 4000
                    val input = socket.getInputStream()
                    fun line(): String {
                        val bytes = ByteArrayOutputStream()
                        while (true) {
                            val next = input.read(); check(next >= 0)
                            if (next == 10) return bytes.toString("UTF-8").trimEnd('\r')
                            bytes.write(next)
                        }
                    }
                    val first = line(); val headers = linkedMapOf<String, String>()
                    while (true) { val header = line(); if (header.isEmpty()) break
                        headers[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim() }
                    val bytes = ByteArray(headers["content-length"]!!.toInt()); var read = 0
                    while (read < bytes.size) { val count = input.read(bytes, read, bytes.size - read); check(count > 0); read += count }
                    calls.incrementAndGet()
                    val reply = handle(Incoming(first, headers, bytes))
                    val outgoing = linkedMapOf("Content-Type" to "application/json", "Content-Length" to reply.body.size.toString(), "Connection" to "close")
                    outgoing.putAll(reply.extraHeaders)
                    val head = "HTTP/1.1 ${reply.code} Response\r\n" + outgoing.entries.joinToString("") { "${it.key}: ${it.value}\r\n" } + "\r\n"
                    try { socket.getOutputStream().apply { write(head.toByteArray(Charsets.US_ASCII)); write(reply.body); flush() } }
                    catch (error: IOException) { if (!ignoreDisconnect) throw error }
                    Unit
                }
            } catch (error: Throwable) { if (!server.isClosed) failure.set(error) }
        }
        override fun close() { server.close(); runner.join(2000); failure.get()?.let { throw AssertionError(it) } }
    }
}
