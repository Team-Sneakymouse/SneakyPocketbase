package com.danidipp.sneakypocketbase

import com.danidipp.sneakypocketbase.PocketbaseLifecycleSnapshot.*
import io.github.agrevster.pocketbaseKotlin.PocketbaseClient
import io.github.agrevster.pocketbaseKotlin.models.Record
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import org.bukkit.Bukkit
import java.io.IOException
import java.util.logging.Logger
import kotlin.random.Random

internal class PocketbaseHttpFailure(val code: Int) : IOException("PocketBase HTTP $code")

/** Exception types only: messages and response bodies can contain credentials. */
internal fun Throwable.failureTypes(): String = generateSequence(this) { it.cause }
    .take(3).joinToString(" caused by ") { it.javaClass.simpleName.ifEmpty { "Throwable" } }

internal fun Throwable.isAvailabilityFailure(): Boolean = when (this) {
    is PocketbaseHttpFailure -> code == 401 || code >= 500
    is ResponseException -> response.status.value == 401 || response.status.value >= 500
    is IOException, is HttpRequestTimeoutException, is TimeoutCancellationException -> true
    else -> false
}

internal class RetryBackoff(private val base: Long = 1_000, private val cap: Long = 60_000,
                            private val credentialBase: Long = 30_000, private val credentialCap: Long = 300_000) {
    private var failures = 0
    fun reset() { failures = 0 }
    fun nextDelay(rejected: Boolean = false): Long {
        val floor = if (rejected) maxOf(base, credentialBase) else base
        val ceiling = if (rejected) maxOf(cap, credentialCap) else cap
        val delay = (floor * (1L shl failures.coerceAtMost(16))).coerceAtMost(ceiling)
        failures = (failures + 1).coerceAtMost(16)
        return Random.nextLong(maxOf(1, delay / 2), delay + 1)
    }
}

/** One replaceable client. Desired subscriptions, revisions and generations live in the coordinator. */
class PocketbaseHandler internal constructor(
    private val logger: Logger,
    pbProtocol: String,
    pbHost: String,
    private val pbUser: String,
    private val pbPassword: String,
    serverName: String?,
    private val lifecycle: PocketbaseLifecycle,
    private val owner: Long,
    private val scope: CoroutineScope = SneakyPocketbase.asyncScope,
    private val recordDelivery: (AsyncPocketbaseEvent) -> Unit = { Bukkit.getPluginManager().callEvent(it) },
    private val backoffFactory: () -> RetryBackoff = { RetryBackoff() },
    private val subscriptionBackoffFactory: () -> RetryBackoff = { RetryBackoff(5_000, 300_000) },
) {
    val pocketbase = PocketbaseClient({ takeFrom("$pbProtocol://$pbHost") })
    private val authenticated = CompletableDeferred<Unit>()
    private val recover = Channel<Unit>(Channel.CONFLATED)
    private val subscriptionsChanged = Channel<Unit>(Channel.CONFLATED)
    private var supervisor: Job? = null
    @Volatile private var stopped = false
    @Volatile private var token = ""
    private var lastFailureLog = 0L

    val isConnected: Boolean get() = lifecycle.owns(owner) && lifecycle.snapshot().transportState == TransportState.CONNECTED
    val isAuthenticated: Boolean get() = lifecycle.owns(owner) && lifecycle.snapshot().apiState == ApiState.AVAILABLE
    val status: String get() = lifecycle.snapshot().let { "${it.apiState}/${it.transportState}" }

    init {
        // Covers requests from the Java adapter and internal record consumers alike.
        pocketbase.httpClient.plugin(HttpSend).intercept { request ->
            val generation = lifecycle.snapshot().generation
            val managedRequest = request.url.encodedPath == "/api/realtime" ||
                request.url.encodedPath.startsWith("/api/collections/_superusers/auth-")
            request.headers.remove(HttpHeaders.UserAgent)
            request.headers.append(HttpHeaders.UserAgent, "SneakyPocketbase" + (serverName?.let { "/$it" } ?: ""))
            if (token.isNotEmpty()) {
                request.headers.remove(HttpHeaders.Authorization)
                request.headers.append(HttpHeaders.Authorization, token)
            }
            try {
                val call = execute(request)
                val code = call.response.status.value
                if (!managedRequest && (code == 401 || code >= 500)) requestFailed(generation, PocketbaseHttpFailure(code))
                call
            } catch (failure: Exception) {
                if (!managedRequest && failure.isAvailabilityFailure()) requestFailed(generation, failure)
                throw failure
            }
        }
    }

    fun onLoaded(callback: Runnable) {
        authenticated.invokeOnCompletion { failure ->
            if (failure == null && !stopped) scope.launch {
                if (!stopped && lifecycle.owns(owner)) {
                    try { callback.run() } catch (error: Exception) { logger.warning("PocketBase authentication callback failed") }
                }
            }
        }
    }

    internal fun subscriptionsChanged() { subscriptionsChanged.trySend(Unit) }

    fun runRealtime() {
        if (supervisor?.isActive == true || stopped) return
        supervisor = scope.launch(CoroutineName("PocketbaseRecovery")) { supervise() }
    }

    fun stop() {
        stopped = true
        supervisor?.cancel()
        authenticated.cancel()
        pocketbase.httpClient.close()
        runBlocking { withTimeoutOrNull(1_000) { supervisor?.join() } }
    }

    internal fun requestFailed(generation: Long, failure: Throwable) {
        if (stopped || !failure.isAvailabilityFailure()) return
        val authenticationRejected = when (failure) {
            is PocketbaseHttpFailure -> failure.code == 401
            is ResponseException -> failure.response.status.value == 401
            else -> false
        }
        if (lifecycle.update(owner, generation,
                api = if (authenticationRejected) ApiState.AUTHENTICATION_FAILED else ApiState.UNAVAILABLE,
                transport = TransportState.DISCONNECTED, resetCollections = true,
                reason = if (authenticationRejected) "authentication rejected" else "API transport failure")) {
            if (authenticationRejected) token = ""
            recover.trySend(Unit)
        }
    }

    private suspend fun supervise() {
        val backoff = backoffFactory()
        while (currentCoroutineContext().isActive && lifecycle.owns(owner)) {
            while (recover.tryReceive().isSuccess) { }
            val startedAt = System.nanoTime()
            val failure = try {
                if (lifecycle.snapshot().apiState != ApiState.AVAILABLE) authenticateOrProbe()
                runSession()
                null
            } catch (error: CancellationException) {
                if (!currentCoroutineContext().isActive) throw error
                error
            } catch (error: Exception) { error }
            if (!lifecycle.owns(owner)) return
            if (System.nanoTime() - startedAt >= 60_000_000_000L) backoff.reset()
            if (failure is PocketbaseHttpFailure && failure.code == 401) token = ""
            val rejected = failure is PocketbaseHttpFailure && failure.code in listOf(400, 401, 403) && token.isEmpty()
            lifecycle.update(owner,
                api = when {
                    rejected -> ApiState.AUTHENTICATION_FAILED
                    failure?.isAvailabilityFailure() == true -> ApiState.UNAVAILABLE
                    else -> null
                },
                transport = TransportState.DISCONNECTED, resetCollections = true,
                reason = when { rejected -> "credentials rejected"; failure != null -> "connection failed"; else -> "realtime disconnected" })
            if (failure != null && (lastFailureLog == 0L || System.nanoTime() - lastFailureLog > 300_000_000_000L)) {
                val summary = if (rejected) "PocketBase credentials rejected" else "PocketBase connection failed"
                logger.warning("$summary (${failure.failureTypes()}); retrying with backoff")
                lastFailureLog = System.nanoTime()
            }
            delay(backoff.nextDelay(rejected))
        }
    }

    private suspend fun authenticateOrProbe() {
        val generation = lifecycle.snapshot().generation
        if (token.isNotEmpty()) {
            try {
                withTimeout(30_000) {
                    val response = pocketbase.httpClient.post("/api/collections/_superusers/auth-refresh").requireSuccess().bodyAsText()
                    if (!lifecycle.owns(owner) || lifecycle.snapshot().generation != generation) throw IOException("Recovery probe superseded")
                    token = Json.parseToJsonElement(response).jsonObject.getValue("token").jsonPrimitive.content
                }
            } catch (failure: PocketbaseHttpFailure) {
                if (failure.code !in listOf(400, 401, 403)) throw failure
                lifecycle.update(owner, generation, api = ApiState.AUTHENTICATION_FAILED, reason = "authentication refresh rejected")
                token = ""
            }
        }
        if (token.isEmpty()) {
            lifecycle.update(owner, api = ApiState.AUTHENTICATING, reason = "authenticating")
            val response = withTimeout(30_000) {
                pocketbase.httpClient.post("/api/collections/_superusers/auth-with-password") {
                    contentType(ContentType.Application.Json)
                    setBody(buildJsonObject { put("identity", pbUser); put("password", pbPassword) }.toString())
                }.requireSuccess().bodyAsText()
            }
            val newToken = Json.parseToJsonElement(response).jsonObject.getValue("token").jsonPrimitive.content
            if (!lifecycle.owns(owner) || lifecycle.snapshot().generation != generation) throw IOException("Authentication attempt superseded")
            token = newToken
        }
        if (!lifecycle.update(owner, generation, api = ApiState.AVAILABLE, reason = "authenticated")) throw IOException("Recovery probe superseded")
        authenticated.complete(Unit)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun runSession() = coroutineScope {
        val generation = lifecycle.attempt(owner) ?: return@coroutineScope
        val connected = CompletableDeferred<String>()
        val stream = async(CoroutineName("PocketbaseRealtimeStream")) {
            try {
                pocketbase.httpClient.prepareGet("/api/realtime") {
                    header(HttpHeaders.Accept, "text/event-stream")
                    // SSE lasts for the whole session; readSse still bounds idle reads.
                    timeout { requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS }
                }.execute { response ->
                    response.requireSuccess()
                    readSse(response.bodyAsChannel()) { event, data ->
                        if (!lifecycle.owns(owner)) return@readSse
                        if (event == "PB_CONNECT") {
                            val id = Json.parseToJsonElement(data).jsonObject.getValue("clientId").jsonPrimitive.content
                            if (lifecycle.update(owner, generation, transport = TransportState.CONNECTED, reason = "realtime connected")) {
                                connected.complete(id)
                            }
                        } else if (connected.isCompleted) {
                            val payload = Json.parseToJsonElement(data).jsonObject
                            val action = payload["action"]?.jsonPrimitive?.content ?: return@readSse
                            val record = payload["record"]?.jsonObject ?: return@readSse
                            val name = record["collectionName"]?.jsonPrimitive?.content ?: ""
                            val snapshot = lifecycle.snapshot()
                            if (snapshot.isCollectionReady(name)) {
                                try {
                                    recordDelivery(AsyncPocketbaseEvent(true, AsyncPocketbaseEvent.Action.valueOf(action.uppercase()), name, record.toString(), snapshot.generation))
                                } catch (failure: Exception) {
                                    logger.warning("PocketBase record listener failed: ${failure.javaClass.simpleName}")
                                }
                            }
                        }
                    }
                }
            } catch (failure: Exception) {
                // Publish loss before waiting for sibling subscription requests to be cancelled.
                if (failure.isAvailabilityFailure()) requestFailed(lifecycle.snapshot().generation, failure)
                throw failure
            } finally {
                lifecycle.update(owner, transport = TransportState.DISCONNECTED, resetCollections = true, reason = "realtime disconnected")
            }
        }
        var applier: Job? = null
        try {
            val id = select<String?> {
                connected.onAwait { it }
                stream.onAwait { null }
                recover.onReceive { null }
                onTimeout(30_000) { throw IOException("Realtime connection timed out") }
            } ?: return@coroutineScope
            applier = launch(CoroutineName("PocketbaseSubscriptions")) { applySubscriptions(id) }
            select<Unit> {
                stream.onAwait { }
                applier.onJoin { }
                recover.onReceive { }
            }
        } finally {
            applier?.cancel()
            stream.cancel()
        }
    }

    private suspend fun applySubscriptions(clientId: String) {
        val accepted = linkedSetOf<String>()
        val retryAt = mutableMapOf<String, Long>()
        val retries = mutableMapOf<String, RetryBackoff>()
        while (currentCoroutineContext().isActive && lifecycle.owns(owner)) {
            val desired = lifecycle.snapshot().collections.keys
            retryAt.keys.retainAll(desired)
            retries.keys.retainAll(desired)
            if (!desired.containsAll(accepted)) {
                val remaining = accepted.intersect(desired)
                setSubscriptions(clientId, remaining)
                accepted.retainAll(desired)
            }
            for (name in desired - accepted) {
                if ((retryAt[name] ?: 0) > System.nanoTime()) continue
                val generation = lifecycle.snapshot().generation
                try {
                    withTimeout(30_000) {
                        pocketbase.httpClient.get("/api/collections/${name.encodeURLPathPart()}/records") { parameter("perPage", 1) }.requireSuccess()
                        setSubscriptions(clientId, accepted + name)
                    }
                    // Track what the server accepted even if intent changed during the request,
                    // so the next pass can remove a concurrently unregistered topic.
                    accepted.add(name)
                    if (lifecycle.collection(owner, generation, name, SubscriptionState.READY, "subscription accepted")) {
                        retryAt.remove(name)
                        retries.remove(name)
                    } else {
                        accepted.remove(name)
                        // Force the server set back to the known current registrations before retrying.
                        setSubscriptions(clientId, accepted.intersect(lifecycle.snapshot().collections.keys))
                        subscriptionsChanged.trySend(Unit)
                    }
                } catch (failure: CancellationException) {
                    if (!currentCoroutineContext().isActive) throw failure
                    val timeout = IOException("Subscription request timed out", failure)
                    requestFailed(lifecycle.snapshot().generation, timeout)
                    throw timeout
                } catch (failure: Exception) {
                    if (failure.isAvailabilityFailure()) {
                        requestFailed(lifecycle.snapshot().generation, failure)
                        throw failure
                    }
                    val reason = if (failure is PocketbaseHttpFailure) "subscription rejected (HTTP ${failure.code})" else "subscription application failed"
                    lifecycle.collection(owner, generation, name, SubscriptionState.FAILED, reason)
                    retryAt[name] = System.nanoTime() + retries.getOrPut(name, subscriptionBackoffFactory).nextDelay() * 1_000_000
                }
            }
            val waitMillis = retryAt.values.minOrNull()?.let { ((it - System.nanoTime()) / 1_000_000).coerceAtLeast(1) }
            if (waitMillis == null) subscriptionsChanged.receive()
            else withTimeoutOrNull(waitMillis) { subscriptionsChanged.receive() }
        }
    }

    private suspend fun setSubscriptions(clientId: String, names: Set<String>) {
        val response = withTimeout(30_000) {
            pocketbase.httpClient.post("/api/realtime") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("clientId", clientId)
                    putJsonArray("subscriptions") { names.forEach { add("$it/*") } }
                }.toString())
            }
        }
        // 403 here means the client's authorization changed; 404 means the SSE client expired.
        if (response.status.value == 403) {
            requestFailed(lifecycle.snapshot().generation, PocketbaseHttpFailure(401))
            throw PocketbaseHttpFailure(401)
        }
        if (response.status.value == 404) throw IOException("Realtime client expired")
        response.requireSuccess()
    }
}

internal fun HttpResponse.requireSuccess(): HttpResponse {
    if (!status.isSuccess()) throw PocketbaseHttpFailure(status.value)
    return this
}

/** Parse SSE framing, including comments and multiline data. Bound idle reads for half-open sockets. */
internal suspend fun readSse(channel: ByteReadChannel, consume: suspend (String, String) -> Unit) {
    var event = "message"
    val data = mutableListOf<String>()
    while (true) {
        val line = withTimeout(360_000) { channel.readUTF8Line() } ?: return
        if (line.isEmpty()) {
            if (data.isNotEmpty()) consume(event, data.joinToString("\n"))
            event = "message"
            data.clear()
        } else if (!line.startsWith(":")) {
            val field = line.substringBefore(':')
            val value = line.substringAfter(':', "").removePrefix(" ")
            when (field) { "event" -> event = value; "data" -> data.add(value) }
        }
    }
}

@Serializable
open class BaseRecord(@Transient open val recordId: String? = null): Record(recordId) {
    fun <T: BaseRecord> toJson(serializer: KSerializer<T>): String {
        @Suppress("UNCHECKED_CAST")
        return Json.encodeToString(serializer, this as T)
    }
}
