package com.danidipp.sneakypocketbase

import com.danidipp.sneakypocketbase.PocketbaseLifecycleSnapshot.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** Plugin-owned state, independent of a particular HTTP client or realtime session. */
internal class PocketbaseLifecycle(
    private val deliver: (PocketbaseLifecycleSnapshot, PocketbaseLifecycleSnapshot) -> Unit,
    private val deliveryFailed: (Throwable) -> Unit,
) {
    private val lock = Any()
    private val events = Channel<Pair<PocketbaseLifecycleSnapshot, PocketbaseLifecycleSnapshot>>(Channel.UNLIMITED)
    private val deliveryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var deliveryJob: Job? = null
    private var owner = 0L
    private var current = PocketbaseLifecycleSnapshot(ApiState.STARTING, TransportState.DISCONNECTED, emptyMap(), 0, 0, "startup")

    fun snapshot(): PocketbaseLifecycleSnapshot = synchronized(lock) { current }
    fun owns(id: Long): Boolean = synchronized(lock) { id == owner && current.apiState != ApiState.STOPPED }

    fun startDelivery() {
        check(deliveryJob == null)
        deliveryJob = deliveryScope.launch {
            for ((previous, next) in events) {
                try { deliver(previous, next) } catch (failure: Exception) { deliveryFailed(failure) }
            }
        }
    }

    fun beginHandler(): Long = synchronized(lock) {
        owner++
        publish(ApiState.UNAVAILABLE, TransportState.DISCONNECTED, pendingCollections(), "handler restart", true)
        owner
    }

    fun register(collection: String) = synchronized(lock) {
        require(collection.isNotBlank()) { "Collection must not be blank" }
        check(current.apiState != ApiState.STOPPED) { "PocketBase is stopped" }
        if (!current.collections.containsKey(collection)) {
            publish(collections = current.collections + (collection to CollectionStatus(SubscriptionState.PENDING, "registered")))
        }
    }

    fun unregister(collection: String) = synchronized(lock) {
        check(current.apiState != ApiState.STOPPED) { "PocketBase is stopped" }
        if (current.collections.containsKey(collection)) publish(collections = current.collections - collection)
    }

    fun attempt(id: Long): Long? = synchronized(lock) {
        if (!owns(id)) return null
        publish(transport = TransportState.CONNECTING, reason = "connection attempt", invalidate = true)
        current.generation
    }

    fun update(id: Long, generation: Long? = null, api: ApiState? = null,
               transport: TransportState? = null, reason: String = "state changed",
               resetCollections: Boolean = false): Boolean = synchronized(lock) {
        if (!owns(id) || (generation != null && generation != current.generation)) return false
        publish(api ?: current.apiState, transport ?: current.transportState, if (resetCollections) pendingCollections() else current.collections, reason)
        true
    }

    fun collection(id: Long, generation: Long, name: String, state: SubscriptionState, reason: String): Boolean = synchronized(lock) {
        if (!owns(id) || generation != current.generation || !current.collections.containsKey(name)) return false
        publish(collections = current.collections + (name to CollectionStatus(state, reason)), reason = reason)
        true
    }

    fun stop(): Unit = synchronized(lock) {
        if (current.apiState == ApiState.STOPPED) return
        owner++
        publish(ApiState.STOPPED, TransportState.STOPPED, pendingCollections(), "shutdown", true)
    }

    fun finishDelivery() {
        if (deliveryJob == null) startDelivery()
        events.close()
        runBlocking { withTimeoutOrNull(1_000) { deliveryJob?.join() } }
        // A slow listener must not silently erase queued transitions, including shutdown.
        deliveryJob?.invokeOnCompletion { deliveryScope.cancel() }
    }

    private fun pendingCollections() = current.collections.mapValues { CollectionStatus(SubscriptionState.PENDING, "connection unavailable") }

    // Called under lock. Updating the snapshot and queueing its event are one atomic operation.
    private fun publish(api: ApiState = current.apiState, transport: TransportState = current.transportState,
                        collections: Map<String, CollectionStatus> = current.collections,
                        reason: String = "state changed", invalidate: Boolean = false) {
        val previous = current
        val changed = api != previous.apiState || transport != previous.transportState || collections != previous.collections
        if (!changed && !invalidate) return
        val lost = (previous.apiState == ApiState.AVAILABLE && api != ApiState.AVAILABLE) ||
            (previous.transportState == TransportState.CONNECTED && transport != TransportState.CONNECTED) ||
            previous.collections.any { (name, status) -> status.state() == SubscriptionState.READY && collections[name]?.state() != SubscriptionState.READY }
        current = PocketbaseLifecycleSnapshot(api, transport, collections,
            previous.generation + if (invalidate || lost) 1 else 0, previous.revision + 1, reason)
        check(events.trySend(previous to current).isSuccess)
    }
}
