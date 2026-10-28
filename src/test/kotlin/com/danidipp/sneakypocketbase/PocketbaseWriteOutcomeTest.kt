package com.danidipp.sneakypocketbase

import kotlinx.coroutines.*
import io.ktor.http.takeFrom
import io.ktor.client.plugins.plugin
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.*

class PocketbaseWriteOutcomeTest {
    private fun httpFailure(code: Int, body: String): PocketbaseWriteFailure {
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        val requests = java.util.concurrent.atomic.AtomicInteger()
        server.createContext("/") { exchange ->
            requests.incrementAndGet()
            exchange.requestBody.readAllBytes()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(code, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server.start()
        val client = io.github.agrevster.pocketbaseKotlin.PocketbaseClient({ takeFrom("http://127.0.0.1:${server.address.port}") })
        val lifecycle = PocketbaseLifecycle({ _, _ -> }, {})
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val reported = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val api = PocketbaseApiAdapter({ scope }, { client }, {}, {}, {}, { lifecycle.snapshot() }, { _, failure -> reported.add(failure) })
        try {
            val failure = assertFailsWith<ExecutionException> { api.create("mail", "{\"secret\":true}").get(5, TimeUnit.SECONDS) }.cause
            assertEquals(code, assertIs<PocketbaseHttpFailure>(reported.single()).code, "Preserve existing availability classification")
            assertEquals(1, requests.get(), "Create must not retry")
            return assertIs<PocketbaseWriteFailure>(failure)
        } finally { scope.cancel(); client.httpClient.close(); server.stop(0); lifecycle.finishDelivery() }
    }

    @Test fun `standard structured create rejections expose status without leaking body`() {
        for (code in listOf(400, 401, 403)) {
            val failure = httpFailure(code, """{"status":$code,"message":"secret server response","data":{}}""")
            assertEquals(PocketbaseWriteFailure.Outcome.REJECTED, failure.outcome)
            assertEquals(code, failure.statusCode)
            assertFalse(failure.message!!.contains("secret"))
            assertNotNull(failure.cause)
        }
    }

    @Test fun `ambiguous HTTP responses including 404 never authorize resend`() {
        for ((code, body) in listOf(400 to "proxy error", 403 to "{}", 404 to "{}", 503 to "{}")) {
            val failure = httpFailure(code, body)
            assertEquals(PocketbaseWriteFailure.Outcome.UNKNOWN, failure.outcome)
            assertEquals(code, failure.statusCode)
        }
    }

    @Test fun `failures after operation admission are unknown and retain original causes`() {
        val failures = listOf<Throwable>(IOException("secret"), java.net.ConnectException("refused"), CancellationException("secret cancellation"))
        for (cause in failures) {
            val lifecycle = PocketbaseLifecycle({ _, _ -> }, {})
            val client = io.github.agrevster.pocketbaseKotlin.PocketbaseClient({ takeFrom("http://127.0.0.1:1") })
            client.httpClient.plugin(io.ktor.client.plugins.HttpSend).intercept { throw cause }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val api = PocketbaseApiAdapter({ scope }, { client }, {}, {}, {}, { lifecycle.snapshot() }, { _, _ -> })
            try {
                val failure = assertIs<PocketbaseWriteFailure>(assertFailsWith<ExecutionException> { api.create("mail", "{}").get(5, TimeUnit.SECONDS) }.cause)
                assertEquals(PocketbaseWriteFailure.Outcome.UNKNOWN, failure.outcome)
                assertTrue(generateSequence(failure.cause) { it.cause }.any { it === cause }, "Original cause retained through coroutine stack recovery")
                assertNull(failure.statusCode)
            } finally { scope.cancel(); client.httpClient.close(); lifecycle.finishDelivery() }
        }
    }

    @Test fun `scope admission denial is not sent`() {
        val lifecycle = PocketbaseLifecycle({ _, _ -> }, {})
        val client = io.github.agrevster.pocketbaseKotlin.PocketbaseClient({ takeFrom("http://127.0.0.1:1") })
        val cause = IllegalStateException("secret scope denial")
        val api = PocketbaseApiAdapter({ throw cause }, { client }, {}, {}, {}, { lifecycle.snapshot() }, { _, _ -> })
        try {
            val failure = assertIs<PocketbaseWriteFailure>(assertFailsWith<ExecutionException> { api.create("mail", "{}").get() }.cause)
            assertEquals(PocketbaseWriteFailure.Outcome.NOT_SENT, failure.outcome)
            assertSame(cause, failure.cause)
        } finally { client.httpClient.close(); lifecycle.finishDelivery() }
    }

    @Test fun `cancelled scope still completes create with public outcome`() {
        val lifecycle = PocketbaseLifecycle({ _, _ -> }, {})
        val client = io.github.agrevster.pocketbaseKotlin.PocketbaseClient({ takeFrom("http://127.0.0.1:1") })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { it.cancel() }
        val api = PocketbaseApiAdapter({ scope }, { client }, {}, {}, {}, { lifecycle.snapshot() }, { _, _ -> })
        try {
            val failure = assertIs<PocketbaseWriteFailure>(assertFailsWith<ExecutionException> { api.create("mail", "{}").get(5, TimeUnit.SECONDS) }.cause)
            assertEquals(PocketbaseWriteFailure.Outcome.UNKNOWN, failure.outcome)
        } finally { client.httpClient.close(); lifecycle.finishDelivery() }
    }

    @Test fun `timeout after start is unknown`() {
        val lifecycle = PocketbaseLifecycle({ _, _ -> }, {})
        val client = io.github.agrevster.pocketbaseKotlin.PocketbaseClient({ takeFrom("http://127.0.0.1:1") })
        val started = java.util.concurrent.CountDownLatch(1)
        client.httpClient.plugin(io.ktor.client.plugins.HttpSend).intercept {
            started.countDown()
            withTimeout(10) { awaitCancellation() }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val api = PocketbaseApiAdapter({ scope }, { client }, {}, {}, {}, { lifecycle.snapshot() }, { _, _ -> })
        try {
            val failure = assertIs<PocketbaseWriteFailure>(assertFailsWith<ExecutionException> { api.create("mail", "{}").get(5, TimeUnit.SECONDS) }.cause)
            assertEquals(0, started.count)
            assertEquals(PocketbaseWriteFailure.Outcome.UNKNOWN, failure.outcome)
            assertTrue(generateSequence(failure.cause) { it.cause }.any { it is TimeoutCancellationException })
        } finally { scope.cancel(); client.httpClient.close(); lifecycle.finishDelivery() }
    }

    @Test fun `generation invalidation after create completes is unknown`() {
        val lifecycle = PocketbaseLifecycle({ _, _ -> }, {})
        lifecycle.beginHandler()
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.requestBody.readAllBytes()
            exchange.sendResponseHeaders(200, 2)
            exchange.responseBody.use { it.write("{}".toByteArray()) }
        }
        server.start()
        val client = io.github.agrevster.pocketbaseKotlin.PocketbaseClient({ takeFrom("http://127.0.0.1:${server.address.port}") })
        client.httpClient.plugin(io.ktor.client.plugins.HttpSend).intercept { request ->
            val call = execute(request)
            lifecycle.beginHandler()
            call
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val api = PocketbaseApiAdapter({ scope }, { client }, {}, {}, {}, { lifecycle.snapshot() }, { _, _ -> })
        try {
            val failure = assertIs<PocketbaseWriteFailure>(assertFailsWith<ExecutionException> { api.create("mail", "{}").get(5, TimeUnit.SECONDS) }.cause)
            assertEquals(PocketbaseWriteFailure.Outcome.UNKNOWN, failure.outcome)
        } finally { scope.cancel(); client.httpClient.close(); server.stop(0); lifecycle.finishDelivery() }
    }

    @Test fun `future cancellation is not evidence of not sent and does not cancel coroutine`() {
        val lifecycle = PocketbaseLifecycle({ _, _ -> }, {})
        val client = io.github.agrevster.pocketbaseKotlin.PocketbaseClient({ takeFrom("http://127.0.0.1:1") })
        val started = java.util.concurrent.CountDownLatch(1)
        val finished = java.util.concurrent.CountDownLatch(1)
        val release = CompletableDeferred<Unit>()
        client.httpClient.plugin(io.ktor.client.plugins.HttpSend).intercept {
            started.countDown()
            release.await()
            finished.countDown()
            throw IOException("response lost")
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val api = PocketbaseApiAdapter({ scope }, { client }, {}, {}, {}, { lifecycle.snapshot() }, { _, _ -> })
        try {
            val future = api.create("mail", "{}")
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertTrue(future.cancel(true))
            assertFailsWith<java.util.concurrent.CancellationException> { future.join() }
            release.complete(Unit)
            assertTrue(finished.await(5, TimeUnit.SECONDS), "Cancellation of observer does not stop admitted write")
        } finally { release.complete(Unit); scope.cancel(); client.httpClient.close(); lifecycle.finishDelivery() }
    }

    @Test fun `denied client admission exposes sanitized public not sent failure`() {
        val cause = IOException("secret payload and raw response")
        val lifecycle = PocketbaseLifecycle({ _, _ -> }, {})
        val api = PocketbaseApiAdapter(
            scope = { error("must not launch") }, client = { throw cause }, ready = {},
            subscribeAction = {}, unsubscribeAction = {}, lifecycle = { lifecycle.snapshot() }, operationFailed = { _, _ -> },
        )
        try {
            val failure = assertFailsWith<ExecutionException> { api.create("mail", "secret").get(1, TimeUnit.SECONDS) }.cause!!
            assertEquals("com.danidipp.sneakypocketbase.PocketbaseWriteFailure", failure.javaClass.name)
            assertIs<IOException>(failure)
            assertEquals("NOT_SENT", failure.javaClass.getMethod("getOutcome").invoke(failure).toString())
            assertNull(failure.javaClass.getMethod("getStatusCode").invoke(failure))
            assertSame(cause, failure.cause)
            assertFalse(failure.message!!.contains("secret"))
        } finally { lifecycle.finishDelivery() }
    }
}
