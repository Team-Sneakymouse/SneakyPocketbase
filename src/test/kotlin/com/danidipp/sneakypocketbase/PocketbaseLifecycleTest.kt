package com.danidipp.sneakypocketbase

import com.danidipp.sneakypocketbase.PocketbaseLifecycleSnapshot.*
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import java.util.Collections
import kotlin.test.*

class PocketbaseLifecycleTest {
    private fun coordinator() = PocketbaseLifecycle({ _, _ -> }, { throw AssertionError(it) })

    @Test fun `reload retains registrations and rejects previous handlers and generations`() {
        val lifecycle = coordinator()
        try {
            val owner = lifecycle.beginHandler()
            lifecycle.register("settings")
            lifecycle.update(owner, api = ApiState.AVAILABLE)
            val generation = lifecycle.attempt(owner)!!
            lifecycle.update(owner, generation, transport = TransportState.CONNECTED)
            lifecycle.collection(owner, generation, "settings", SubscriptionState.READY, "accepted")
            val ready = lifecycle.snapshot()
            val nextOwner = lifecycle.beginHandler()
            val restarted = lifecycle.snapshot()
            assertTrue(restarted.revision > ready.revision)
            assertTrue(restarted.generation > ready.generation)
            assertEquals(setOf("settings"), restarted.collections.keys)
            assertFalse(restarted.isCollectionReady("settings"))
            assertFalse(lifecycle.update(owner, api = ApiState.AVAILABLE))
            assertFalse(lifecycle.collection(nextOwner, generation, "settings", SubscriptionState.READY, "stale"))
            assertEquals(restarted, lifecycle.snapshot())
            assertTrue(ready.isCollectionReady("settings"))
            assertFailsWith<UnsupportedOperationException> { (ready.collections as MutableMap).clear() }
        } finally { lifecycle.finishDelivery() }
    }

    @Test fun `disconnect invalidates work immediately and duplicate disconnect is silent`() {
        val lifecycle = coordinator()
        try {
            val owner = lifecycle.beginHandler()
            lifecycle.register("settings")
            lifecycle.update(owner, api = ApiState.AVAILABLE, transport = TransportState.CONNECTED)
            lifecycle.collection(owner, lifecycle.snapshot().generation, "settings", SubscriptionState.READY, "accepted")
            val before = lifecycle.snapshot()
            lifecycle.update(owner, transport = TransportState.DISCONNECTED, resetCollections = true)
            val disconnected = lifecycle.snapshot()
            assertEquals(before.generation + 1, disconnected.generation)
            assertFalse(disconnected.isCollectionReady("settings"))
            lifecycle.update(owner, transport = TransportState.DISCONNECTED, resetCollections = true)
            assertSame(disconnected, lifecycle.snapshot())
            lifecycle.stop()
            val stopped = lifecycle.snapshot()
            lifecycle.stop()
            assertSame(stopped, lifecycle.snapshot())
            assertEquals(ApiState.STOPPED, stopped.apiState)
            assertFalse(lifecycle.update(owner, api = ApiState.AVAILABLE))
        } finally { lifecycle.finishDelivery() }
    }

    @Test fun `collection failure does not block other collections or advertise connect as ready`() {
        val lifecycle = coordinator()
        try {
            val owner = lifecycle.beginHandler()
            lifecycle.update(owner, api = ApiState.AVAILABLE, transport = TransportState.CONNECTED)
            assertTrue(lifecycle.snapshot().collections.isEmpty())
            assertFalse(lifecycle.snapshot().isCollectionReady("good"))
            lifecycle.register("good")
            lifecycle.register("denied")
            val generation = lifecycle.snapshot().generation
            lifecycle.collection(owner, generation, "denied", SubscriptionState.FAILED, "permission denied")
            lifecycle.collection(owner, generation, "good", SubscriptionState.READY, "accepted")
            assertTrue(lifecycle.snapshot().isCollectionReady("good"))
            assertFalse(lifecycle.snapshot().isCollectionReady("denied"))
            assertEquals(ApiState.AVAILABLE, lifecycle.snapshot().apiState)
            val before = lifecycle.snapshot()
            lifecycle.unregister("good")
            assertEquals(before.generation + 1, lifecycle.snapshot().generation)
        } finally { lifecycle.finishDelivery() }
    }

    @Test fun `concurrent publications deliver in revision order with atomic snapshots`() = runBlocking {
        val delivered = Collections.synchronizedList(mutableListOf<Pair<PocketbaseLifecycleSnapshot, PocketbaseLifecycleSnapshot>>())
        val lifecycle = PocketbaseLifecycle({ previous, current ->
            Thread.sleep(1)
            delivered.add(previous to current)
        }, { throw AssertionError(it) })
        lifecycle.startDelivery()
        lifecycle.beginHandler()
        coroutineScope { repeat(40) { index -> launch(Dispatchers.Default) { lifecycle.register("collection$index") } } }
        val snapshot = lifecycle.snapshot()
        lifecycle.stop()
        lifecycle.finishDelivery()
        assertEquals((1L..snapshot.revision + 1).toList(), delivered.map { it.second.revision })
        delivered.zipWithNext().forEach { (a, b) -> assertSame(a.second, b.first) }
        assertEquals(40, snapshot.collections.size)
    }

    @Test fun `SSE framing handles comments multiline data and end of stream`() = runBlocking {
        val events = mutableListOf<Pair<String, String>>()
        readSse(ByteReadChannel(": comment\r\nevent: PB_CONNECT\r\ndata: {\r\ndata: \"clientId\":\"id\"}\r\n\r\ndata: update\n\n")) { event, data -> events.add(event to data) }
        assertEquals(listOf("PB_CONNECT" to "{\n\"clientId\":\"id\"}", "message" to "update"), events)
    }

    @Test fun `retry delay caps indefinitely and credential rejection retries more slowly`() {
        val normal = RetryBackoff()
        repeat(50) { assertTrue(normal.nextDelay() in 500..60_000) }
        val rejected = RetryBackoff()
        repeat(50) { assertTrue(rejected.nextDelay(true) in 15_000..300_000) }
        normal.reset()
        assertTrue(normal.nextDelay() in 500..1_000)
        assertFalse(PocketbaseHttpFailure(400).isAvailabilityFailure())
        assertFalse(PocketbaseHttpFailure(403).isAvailabilityFailure())
        assertTrue(PocketbaseHttpFailure(401).isAvailabilityFailure())
        assertTrue(PocketbaseHttpFailure(503).isAvailabilityFailure())
    }
}
