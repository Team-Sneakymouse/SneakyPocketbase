package com.danidipp.sneakypocketbase

import com.danidipp.sneakypocketbase.PocketbaseLifecycleSnapshot.*
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.io.OutputStream
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.io.IOException
import kotlin.test.*

class PocketbaseRecoveryTest {
    private class Server : AutoCloseable {
        val authFailures = AtomicInteger()
        val authAttempts = AtomicInteger()
        val refreshes = AtomicInteger()
        val authCode = AtomicInteger(503)
        val deniedCode = AtomicInteger(403)
        val requestCode = AtomicInteger(200)
        val applicationFailures = AtomicInteger()
        val applicationAttempts = AtomicInteger()
        val connections = AtomicInteger()
        val streamFailures = AtomicInteger()
        val streamAttempts = AtomicInteger()
        val heartbeats = AtomicBoolean(true)
        val clients = ConcurrentHashMap<String, OutputStream>()
        val subscriptions = ConcurrentHashMap<String, Set<String>>()
        val slowStarted = CountDownLatch(1)
        val releaseSlow = CountDownLatch(1)
        val holdApplication = AtomicInteger()
        val applicationStarted = CountDownLatch(1)
        val releaseApplication = CountDownLatch(1)
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = this@Server.executor
            createContext("/") { exchange ->
                try { handle(exchange) } catch (_: Exception) { exchange.close() }
            }
            start()
        }
        val host get() = "127.0.0.1:${http.address.port}"

        private fun handle(exchange: HttpExchange) {
            when (exchange.requestURI.path) {
                "/api/collections/_superusers/auth-with-password" -> {
                    authAttempts.incrementAndGet()
                    if (authFailures.getAndUpdate { maxOf(0, it - 1) } > 0) reply(exchange, authCode.get(), "{}")
                    else reply(exchange, 200, "{\"token\":\"test-token\"}")
                }
                "/api/collections/_superusers/auth-refresh" -> {
                    refreshes.incrementAndGet()
                    reply(exchange, 200, "{\"token\":\"refreshed-token\"}")
                }
                "/api/realtime" -> {
                    if (exchange.requestMethod == "GET") {
                        streamAttempts.incrementAndGet()
                        if (streamFailures.getAndUpdate { maxOf(0, it - 1) } > 0) {
                            reply(exchange, 503, "{}")
                            return
                        }
                        val id = "client-${connections.incrementAndGet()}"
                        exchange.responseHeaders.add("Content-Type", "text/event-stream")
                        exchange.sendResponseHeaders(200, 0)
                        val out = exchange.responseBody
                        clients[id] = out
                        synchronized(out) {
                            out.write("event: PB_CONNECT\ndata: {\"clientId\":\"$id\"}\n\n".toByteArray())
                            out.flush()
                        }
                        try {
                            while (!Thread.currentThread().isInterrupted && clients.containsKey(id)) {
                                Thread.sleep(100)
                                if (heartbeats.get()) synchronized(out) { out.write(": heartbeat\n\n".toByteArray()); out.flush() }
                            }
                        } finally { clients.remove(id); exchange.close() }
                    } else {
                        applicationAttempts.incrementAndGet()
                        val body = Json.parseToJsonElement(exchange.requestBody.readAllBytes().decodeToString()).jsonObject
                        val id = body.getValue("clientId").jsonPrimitive.content
                        val topics = body.getValue("subscriptions").jsonArray.map { it.jsonPrimitive.content }.toSet()
                        if (topics.isNotEmpty() && holdApplication.getAndUpdate { maxOf(0, it - 1) } > 0) {
                            applicationStarted.countDown()
                            releaseApplication.await(5, TimeUnit.SECONDS)
                        }
                        if (applicationFailures.getAndUpdate { maxOf(0, it - 1) } > 0) reply(exchange, 400, "{}")
                        else {
                            subscriptions[id] = topics
                            reply(exchange, 204, "")
                        }
                    }
                }
                "/api/collections/denied/records" -> reply(exchange, deniedCode.get(), "{\"items\":[],\"totalPages\":1}")
                "/api/collections/slow/records/id" -> {
                    slowStarted.countDown()
                    releaseSlow.await(5, TimeUnit.SECONDS)
                    reply(exchange, 200, "{\"id\":\"id\"}")
                }
                else -> reply(exchange, requestCode.get(), "{\"items\":[],\"totalPages\":1}")
            }
        }

        fun sendRecord(collection: String) {
            val data = "event: $collection/*\ndata: {\"action\":\"update\",\"record\":{\"id\":\"id\",\"collectionName\":\"$collection\"}}\n\n".toByteArray()
            clients.values.forEach { out -> synchronized(out) { out.write(data); out.flush() } }
        }

        private fun reply(exchange: HttpExchange, code: Int, body: String) {
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(code, if (code == 204) -1 else body.toByteArray().size.toLong())
            if (code != 204) exchange.responseBody.write(body.toByteArray())
            exchange.close()
        }

        override fun close() {
            releaseSlow.countDown()
            releaseApplication.countDown()
            http.stop(0)
            executor.shutdownNow()
        }
    }

    private class Fixture(val server: Server) : AutoCloseable {
        val failures = CopyOnWriteArrayList<Throwable>()
        val events = CopyOnWriteArrayList<PocketbaseLifecycleSnapshot>()
        val records = CopyOnWriteArrayList<AsyncPocketbaseEvent>()
        val logs = CopyOnWriteArrayList<String>()
        val logger = Logger.getAnonymousLogger().apply {
            addHandler(object : Handler() {
                override fun publish(record: LogRecord) { logs.add(record.message) }
                override fun flush() { }
                override fun close() { }
            })
        }
        val lifecycle = PocketbaseLifecycle({ _, current -> events.add(current) }, { failures.add(it) })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error -> failures.add(error) })
        var handler = newHandler()
        val api = PocketbaseApiAdapter(
            scope = { scope }, client = { handler.pocketbase }, ready = { handler.onLoaded(it) },
            subscribeAction = { lifecycle.register(it); handler.subscriptionsChanged() },
            unsubscribeAction = { lifecycle.unregister(it); handler.subscriptionsChanged() },
            lifecycle = { lifecycle.snapshot() }, operationFailed = { generation, error -> handler.requestFailed(generation, error) },
        )

        init { lifecycle.startDelivery() }

        private fun newHandler() = PocketbaseHandler(
            logger, "http", server.host, "user", "password", null,
            lifecycle, lifecycle.beginHandler(), scope, { records.add(it) },
            { RetryBackoff(10, 50, 10, 50) }, { RetryBackoff(100, 100) },
        )

        fun reload() {
            // Invalidates old work before waiting for old-client teardown.
            val old = handler
            handler = newHandler()
            old.stop()
            handler.runRealtime()
        }

        override fun close() {
            lifecycle.stop()
            handler.stop()
            scope.cancel()
            lifecycle.finishDelivery()
            assertTrue(failures.isEmpty(), failures.toString())
        }
    }

    private suspend fun eventually(condition: () -> Boolean) {
        withTimeout(10_000) { while (!condition()) delay(10) }
    }

    @Test fun `failure diagnostics include types without sensitive messages`() {
        val failure = IOException("Authorization: secret-token", IllegalStateException("private server response"))
        assertEquals("IOException caused by IllegalStateException", failure.failureTypes())
    }

    @Test fun `idle subscribed stream survives the CIO deadline and closed stream recovers promptly`() = runBlocking {
        Server().use { server ->
            server.heartbeats.set(false)
            Fixture(server).use { fixture ->
                fixture.api.subscribe("good").get(1, TimeUnit.SECONDS)
                fixture.handler.runRealtime()
                eventually { fixture.lifecycle.snapshot().isCollectionReady("good") }
                val first = fixture.lifecycle.snapshot()
                delay(17_000)
                val idle = fixture.lifecycle.snapshot()
                assertEquals(first.generation, idle.generation, "Idle SSE must not reconnect after 15 seconds")
                assertEquals(first.revision, idle.revision)
                assertTrue(idle.isCollectionReady("good"))
                assertEquals(1, server.connections.get())
                assertEquals(1, server.authAttempts.get())
                assertTrue(server.subscriptions.values.any { it == setOf("good/*") })

                server.clients.entries.toList().forEach { (id, out) ->
                    server.clients.remove(id)
                    out.close()
                }
                withTimeout(2_000) {
                    while (fixture.events.none {
                        it.revision > first.revision && it.transportState == TransportState.DISCONNECTED && !it.isCollectionReady("good")
                    }) delay(10)
                }
                eventually { fixture.lifecycle.snapshot().isCollectionReady("good") && fixture.lifecycle.snapshot().generation > first.generation }
                server.sendRecord("good")
                eventually { fixture.records.isNotEmpty() }
                assertEquals(fixture.lifecycle.snapshot().generation, fixture.records.single().generation)
            }
        }
    }

    @Test fun `startup survives more than five failures and collection denial is isolated`() = runBlocking {
        Server().use { server ->
            server.authFailures.set(6)
            Fixture(server).use { fixture ->
                fixture.api.subscribe("good").get(1, TimeUnit.SECONDS)
                fixture.api.subscribe("denied").get(1, TimeUnit.SECONDS)
                assertEquals(0, server.authAttempts.get())
                assertEquals(SubscriptionState.PENDING, fixture.api.lifecycleSnapshot.collections["good"]!!.state())
                val callbacks = AtomicInteger()
                fixture.api.whenReady { callbacks.incrementAndGet() }
                fixture.handler.runRealtime()
                eventually { fixture.lifecycle.snapshot().isCollectionReady("good") }
                eventually { fixture.lifecycle.snapshot().collections["denied"]?.state() == SubscriptionState.FAILED }
                assertTrue(server.authAttempts.get() > 5)
                assertEquals(1, fixture.logs.count { it.startsWith("PocketBase connection failed (PocketbaseHttpFailure)") })
                eventually { callbacks.get() == 1 }
                assertTrue(server.subscriptions.values.any { it == setOf("good/*") })
                val revision = fixture.lifecycle.snapshot().revision
                fixture.api.getOne("denied", "id").handle { _, _ -> Unit }.get(1, TimeUnit.SECONDS)
                assertEquals(revision, fixture.lifecycle.snapshot().revision)
                server.deniedCode.set(200)
                eventually { fixture.lifecycle.snapshot().isCollectionReady("denied") }
                val generation = fixture.lifecycle.snapshot().generation
                server.sendRecord("good")
                eventually { fixture.records.isNotEmpty() }
                assertEquals(generation, fixture.records.single().generation)
                assertEquals(AsyncPocketbaseEvent.Action.UPDATE, fixture.records.single().action)
            }
        }
    }

    @Test fun `credential rejection recovers and application failure retries without reporting ready early`() = runBlocking {
        Server().use { server ->
            server.authFailures.set(7)
            server.authCode.set(400)
            server.applicationFailures.set(1)
            Fixture(server).use { fixture ->
                fixture.api.subscribe("good").get(1, TimeUnit.SECONDS)
                fixture.handler.runRealtime()
                eventually { fixture.events.any { it.apiState == ApiState.AUTHENTICATION_FAILED } }
                eventually { fixture.events.any { it.collections["good"]?.state() == SubscriptionState.FAILED } }
                eventually { fixture.lifecycle.snapshot().isCollectionReady("good") }
                assertTrue(server.authAttempts.get() > 5)
                assertTrue(server.applicationAttempts.get() >= 2)
                val readyIndex = fixture.events.indexOfFirst { it.isCollectionReady("good") }
                assertTrue(fixture.events.take(readyIndex).any { it.transportState == TransportState.CONNECTED && !it.isCollectionReady("good") })
                val attempts = server.authAttempts.get()
                delay(200)
                assertEquals(attempts, server.authAttempts.get())
                assertEquals(0, server.refreshes.get())
            }
        }
    }

    @Test fun `request outage reauthenticates and reload rejects stale results while preserving registrations`() = runBlocking {
        Server().use { server ->
            Fixture(server).use { fixture ->
                fixture.api.subscribe("good").get(1, TimeUnit.SECONDS)
                fixture.handler.runRealtime()
                eventually { fixture.lifecycle.snapshot().isCollectionReady("good") }
                val original = fixture.lifecycle.snapshot()
                server.requestCode.set(401)
                fixture.api.getOne("good", "id").handle { _, _ -> Unit }.get(2, TimeUnit.SECONDS)
                eventually { fixture.events.any { it.apiState == ApiState.AUTHENTICATION_FAILED } }
                server.requestCode.set(200)
                eventually { fixture.lifecycle.snapshot().isCollectionReady("good") }
                assertTrue(server.authAttempts.get() >= 2)
                assertTrue(fixture.lifecycle.snapshot().generation > original.generation)
                val slow = fixture.api.getOne("slow", "id")
                assertTrue(server.slowStarted.await(2, TimeUnit.SECONDS))
                val beforeReload = fixture.lifecycle.snapshot()
                fixture.reload()
                server.releaseSlow.countDown()
                eventually { fixture.lifecycle.snapshot().isCollectionReady("good") }
                assertTrue(slow.isCompletedExceptionally)
                assertTrue(fixture.lifecycle.snapshot().revision > beforeReload.revision)
                assertTrue(fixture.lifecycle.snapshot().generation > beforeReload.generation)
                assertEquals(setOf("good"), fixture.lifecycle.snapshot().collections.keys)
                fixture.api.unsubscribe("good").get(1, TimeUnit.SECONDS)
                eventually { server.subscriptions.values.any { it.isEmpty() } }
                assertEquals(TransportState.CONNECTED, fixture.lifecycle.snapshot().transportState)
            }
        }
    }

    @Test fun `unregister during application removes the accepted topic from the server`() = runBlocking {
        Server().use { server ->
            server.holdApplication.set(1)
            Fixture(server).use { fixture ->
                fixture.api.subscribe("good").get(1, TimeUnit.SECONDS)
                fixture.handler.runRealtime()
                assertTrue(server.applicationStarted.await(3, TimeUnit.SECONDS))
                assertFalse(fixture.lifecycle.snapshot().isCollectionReady("good"))
                fixture.api.unsubscribe("good").get(1, TimeUnit.SECONDS)
                server.releaseApplication.countDown()
                eventually { server.subscriptions.values.any { it.isEmpty() } }
                assertTrue(fixture.lifecycle.snapshot().collections.isEmpty())
                assertEquals(ApiState.AVAILABLE, fixture.lifecycle.snapshot().apiState)
                assertEquals(TransportState.CONNECTED, fixture.lifecycle.snapshot().transportState)
            }
        }
    }

    @Test fun `startup stream failures retry and disconnect restores subscriptions`() = runBlocking {
        Server().use { server ->
            server.streamFailures.set(6)
            Fixture(server).use { fixture ->
                fixture.api.subscribe("good").get(1, TimeUnit.SECONDS)
                fixture.handler.runRealtime()
                eventually { fixture.lifecycle.snapshot().isCollectionReady("good") }
                assertTrue(server.streamAttempts.get() > 5)
                val first = fixture.lifecycle.snapshot()
                server.clients.values.toList().forEach { it.close() }
                eventually { fixture.lifecycle.snapshot().isCollectionReady("good") && fixture.lifecycle.snapshot().generation > first.generation }
                assertTrue(server.connections.get() >= 2)
                assertTrue(fixture.events.any { it.revision > first.revision && it.transportState == TransportState.DISCONNECTED && !it.isCollectionReady("good") })
                val recovered = fixture.lifecycle.snapshot()
                server.requestCode.set(503)
                fixture.api.getOne("good", "id").handle { _, _ -> Unit }.get(2, TimeUnit.SECONDS)
                eventually { fixture.events.any { it.revision > recovered.revision && it.apiState == ApiState.UNAVAILABLE } }
                server.requestCode.set(200)
                eventually { fixture.lifecycle.snapshot().isCollectionReady("good") }
                assertTrue(server.refreshes.get() > 0)
            }
        }
    }
}
