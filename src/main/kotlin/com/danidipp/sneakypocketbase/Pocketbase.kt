package com.danidipp.sneakypocketbase

import org.bukkit.Bukkit
import io.github.agrevster.pocketbaseKotlin.PocketbaseClient
import io.github.agrevster.pocketbaseKotlin.PocketbaseException
import io.github.agrevster.pocketbaseKotlin.dsl.login
import io.github.agrevster.pocketbaseKotlin.models.AuthRecord
import io.github.agrevster.pocketbaseKotlin.models.Record
import io.github.agrevster.pocketbaseKotlin.services.RealtimeService
import io.ktor.client.plugins.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.serialization.*
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import java.lang.reflect.Field
import java.util.logging.Logger

class PocketbaseHandler {
    companion object {
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val RECONNECT_DELAY_MS = 30_000L
        private const val LISTENER_START_DELAY_MS = 500L
        private const val JOB_JOIN_TIMEOUT_MS = 5_000L
    }

    val pocketbase: PocketbaseClient
    private var authWait: Deferred<Unit>
    private val logger: Logger
    private var realtimeJob: Job? = null
    private val subscriptionLock = Any()
    private val realtimeSubscriptions = linkedSetOf<String>()
    @Volatile
    var isConnected: Boolean = false
    @Volatile
    var isAuthenticated: Boolean = false
    @Volatile
    var status = "PreInit"

    @OptIn(DelicateCoroutinesApi::class)
    constructor(logger: Logger,
                pbProtocol: String,
                pbHost: String,
                pbUser: String,
                pbPassword: String,
                serverName: String? = null) {
        this.logger = logger
        status = "Initializing"
        pocketbase = PocketbaseClient({
            this.protocol = URLProtocol.byName[pbProtocol]!!
            this.host = pbHost
        })
        pocketbase.httpClient.plugin(HttpSend).intercept { request ->
            request.headers.append("User-Agent", "SneakyPocketbase" + (serverName?.let { "/$it" } ?: ""))
            execute(request)
        }

        status = "Authenticating"
        authWait = SneakyPocketbase.asyncScope.async {
            val token = pocketbase.records.authWithPassword<AuthRecord>("_superusers", pbUser, pbPassword).token
            pocketbase.login { this.token = token }
            isAuthenticated = true
        }
        logger.fine("Registering pre-init loaded callbacks")
        for (callback in SneakyPocketbase.preInitLoadedCallbacks) {
            onLoaded(callback)
        }
        SneakyPocketbase.preInitLoadedCallbacks.clear()
        status = "Initialized"
    }

    fun onLoaded(callback: java.lang.Runnable) {
        authWait.invokeOnCompletion { cause ->
            // cause can be null (normal completion), CancellationException (cancelled), or a general error
            if (cause == null && SneakyPocketbase.getInstance().isEnabled) {
                callback.run()
            }
        }
    }

    suspend fun subscribe(subscriptionName: String) {
        val shouldApply = synchronized(subscriptionLock) {
            realtimeSubscriptions.add(subscriptionName)
            isConnected
        }
        logger.info("Registered Pocketbase Realtime subscription '$subscriptionName'")
        if (shouldApply) {
            applyRealtimeSubscriptions("subscription update")
        }
    }

    suspend fun unsubscribe(subscriptionName: String) {
        val shouldApply = synchronized(subscriptionLock) {
            realtimeSubscriptions.remove(subscriptionName)
            isConnected
        }
        logger.info("Removed Pocketbase Realtime subscription '$subscriptionName'")
        if (shouldApply) {
            runCatching {
                pocketbase.realtime.unsubscribe(subscriptionName)
            }.onFailure {
                if (it !is CancellationException) {
                    logger.warning("Failed to send Pocketbase Realtime unsubscribe for '$subscriptionName'")
                    logger.fine(it.stackTraceToString())
                }
            }
        }
    }

    fun stop() {
        status = "Stopping"
        runBlocking {
            val currentRealtimeJob = realtimeJob
            realtimeJob = null
            cancelAndJoinSafely(currentRealtimeJob, "Pocketbase Realtime supervisor")
            cancelAndJoinSafely(authWait, "Pocketbase authentication job")

            logger.info("Disconnecting from Pocketbase Realtime")
            disconnectRealtime("Failed to disconnect from Pocketbase Realtime cleanly")
            markDisconnected()
        }
        status = "Stopped"
    }

    fun runRealtime() {
        logger.info("Starting Pocketbase Realtime")
        status = "Starting Realtime"
        runBlocking {
            val currentRealtimeJob = realtimeJob
            realtimeJob = null
            cancelAndJoinSafely(currentRealtimeJob, "Pocketbase Realtime supervisor")
            disconnectRealtime()
        }
        realtimeJob = SneakyPocketbase.asyncScope.launch outer@{
            if (!awaitAuthentication()) return@outer
            runRealtimeLoop()
        }
    }

    private suspend fun awaitAuthentication(): Boolean {
        val result = runCatching {
            status = "Waiting for authentication"
            authWait.await()
            status = "Authenticated"
            true
        }

        val failure = result.exceptionOrNull()
        if (failure == null) return true

        if (failure is CancellationException || !currentCoroutineContext().isActive) return false

        status = "Authentication failed"
        logger.severe("Failed to authenticate with Pocketbase")
        logger.severe(failure.stackTraceToString())
        Bukkit.getPluginManager().disablePlugin(SneakyPocketbase.getInstance())
        return false
    }

    private suspend fun runRealtimeLoop() {
        var startupFailures = 0
        var hasEstablishedConnection = false

        while (currentCoroutineContext().isActive && SneakyPocketbase.asyncScope.isActive) {
            logger.info("Connecting to Pocketbase Realtime")
            status = "Connecting"

            val result = runRealtimeSession(hasEstablishedConnection)
            markDisconnected()

            if (!currentCoroutineContext().isActive || !SneakyPocketbase.asyncScope.isActive) {
                return
            }

            if (result.established) {
                hasEstablishedConnection = true
                startupFailures = 0
            } else if (!hasEstablishedConnection) {
                startupFailures++
                if (startupFailures >= MAX_RECONNECT_ATTEMPTS) {
                    logger.severe("Failed to connect to Pocketbase Realtime after $MAX_RECONNECT_ATTEMPTS tries")
                    result.failure?.let { logger.severe(it.stackTraceToString()) }
                    Bukkit.getPluginManager().disablePlugin(SneakyPocketbase.getInstance())
                    return
                }
            }

            logReconnect(result.failure, if (hasEstablishedConnection) null else MAX_RECONNECT_ATTEMPTS - startupFailures)
            status = "Waiting to reconnect"
            delay(RECONNECT_DELAY_MS)
        }
    }

    private suspend fun runRealtimeSession(hasConnectedBefore: Boolean): RealtimeSessionResult = coroutineScope {
        disconnectRealtime()

        val sessionEstablished = CompletableDeferred<Unit>()
        val sessionEnded = CompletableDeferred<Throwable?>()
        var announcedConnected = false

        val connectJob = launch(CoroutineName("PocketbaseRealtimeConnect")) {
            runCatching {
                pocketbase.realtime.connect()
            }.onSuccess {
                if (!sessionEnded.isCompleted) sessionEnded.complete(null)
            }.onFailure {
                if (it !is CancellationException && !sessionEnded.isCompleted) sessionEnded.complete(it)
            }
        }

        val listenerJob = launch(CoroutineName("PocketbaseRealtimeListen")) {
            delay(LISTENER_START_DELAY_MS)
            runCatching {
                pocketbase.realtime.listen {
                    if (action == RealtimeService.RealtimeActionType.CONNECT) {
                        if (!sessionEstablished.isCompleted)
                            sessionEstablished.complete(Unit)
                        isConnected = true
                        status = "Connected"
                        if (!announcedConnected) {
                            announcedConnected = true
                            logger.info(if (hasConnectedBefore) "Reconnected to Pocketbase Realtime" else "Connected to Pocketbase Realtime")
                        }
                        launch(CoroutineName("PocketbaseRealtimeSubscribe")) {
                            applyRealtimeSubscriptions("connection established")
                        }
                        return@listen
                    }

                    val record = this.parseRecord<BaseRecord>(Json { ignoreUnknownKeys = true })
                    val collectionName = record.collectionName ?: ""
                    logger.fine("Received Pocketbase Realtime event on ${collectionName}: $action, ${record.id}")
                    Bukkit.getScheduler().runTaskAsynchronously(SneakyPocketbase.getInstance(), Runnable {
                        Bukkit.getPluginManager().callEvent(AsyncPocketbaseEvent(true, action, collectionName, this))
                    })
                }
            }.onSuccess {
                if (!sessionEnded.isCompleted) {
                    sessionEnded.complete(null)
                }
            }.onFailure {
                if (it !is CancellationException && !sessionEnded.isCompleted) {
                    sessionEnded.complete(it)
                }
            }
        }

        val failure = try {
            sessionEnded.await()
        } finally {
            disconnectRealtime()
            cancelAndJoinSafely(listenerJob, "Pocketbase Realtime listener")
            cancelAndJoinSafely(connectJob, "Pocketbase Realtime connector")
        }

        RealtimeSessionResult(
            established = sessionEstablished.isCompleted,
            failure = failure,
        )
    }

    private suspend fun disconnectRealtime(warningMessage: String? = null) {
        val failure = runCatching {
            pocketbase.realtime.disconnect()
        }.exceptionOrNull()

        if (failure != null && failure !is CancellationException) {
            if (warningMessage != null) {
                logger.warning(warningMessage)
                logger.fine(failure.stackTraceToString())
            }
            forceResetRealtimeState()
        }
    }

    private suspend fun applyRealtimeSubscriptions(reason: String) {
        val subscriptions = synchronized(subscriptionLock) {
            realtimeSubscriptions.toList()
        }
        if (subscriptions.isEmpty()) {
            logger.info("No Pocketbase Realtime subscriptions to apply after $reason")
            return
        }

        try {
            pocketbase.realtime.subscribe(2_000L, *subscriptions.toTypedArray())
            logger.info("Applied Pocketbase Realtime subscriptions after $reason: ${subscriptions.joinToString(", ")}")
        } catch (it: CancellationException) {
            throw it
        } catch (it: Exception) {
            if (currentCoroutineContext().isActive) {
                logger.warning("Failed to apply Pocketbase Realtime subscriptions after $reason: ${subscriptions.joinToString(", ")}")
                logger.fine(it.stackTraceToString())
            }
        }
    }

    private suspend fun forceResetRealtimeState() {
        runCatching {
            val realtime = pocketbase.realtime
            val realtimeClass = realtime.javaClass

            getMutableField<MutableSet<Job>>(realtimeClass, realtime, "sseCoroutines")?.toList()?.forEach { job ->
                cancelAndJoinSafely(job, "Pocketbase Realtime SSE job")
            }
            getMutableField<MutableSet<*>>(realtimeClass, realtime, "sseCoroutines")?.clear()
            getMutableField<MutableSet<*>>(realtimeClass, realtime, "subscriptions")?.clear()

            setFieldValue(realtimeClass, realtime, "clientId", null)
            setFieldValue(realtimeClass, realtime, "connected", false)
            logger.warning("Forced Pocketbase Realtime state reset after disconnect failure")
        }.onFailure {
            logger.severe("Failed to force-reset Pocketbase Realtime state")
            logger.severe(it.stackTraceToString())
        }
    }

    private fun setFieldValue(clazz: Class<*>, instance: Any, fieldName: String, value: Any?) {
        val field = clazz.getDeclaredField(fieldName)
        field.isAccessible = true
        field.set(instance, value)
    }

    private fun <T> getMutableField(clazz: Class<*>, instance: Any, fieldName: String): T? {
        val field: Field = clazz.getDeclaredField(fieldName)
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(instance) as? T
    }

    private suspend fun cancelAndJoinSafely(job: Job?, description: String) {
        if (job == null) return
        job.cancel()
        val joined = withTimeoutOrNull(JOB_JOIN_TIMEOUT_MS) {
            job.join()
            true
        } ?: false
        if (!joined) {
            logger.warning("Timed out while waiting for $description to stop")
        }
    }

    private fun markDisconnected() {
        isConnected = false
        status = "Disconnected"
    }

    private fun logReconnect(failure: Throwable?, remainingAttempts: Int?) {
        if (failure != null) {
            if (failure is PocketbaseException && failure.reason.contains("already connected", true)) {
                logger.warning("Pocketbase Realtime reported a stale connection while reconnecting")
            } else {
                logger.warning("Pocketbase Realtime session ended")
            }
            logger.fine(failure.stackTraceToString())
        }

        if (remainingAttempts == null) {
            logger.warning("Disconnected from Pocketbase Realtime. Reconnecting in 30 seconds...")
            return
        }

        logger.warning("Disconnected from Pocketbase Realtime. $remainingAttempts remaining. Reconnecting in 30 seconds...")
    }

    private data class RealtimeSessionResult(
        val established: Boolean,
        val failure: Throwable?,
    )
}

@Serializable
open class BaseRecord(@Transient open val recordId: String? = null): Record(recordId){
    fun <T: BaseRecord> toJson(serializer: KSerializer<T>): String {
        @Suppress("UNCHECKED_CAST")
        return Json.encodeToString(serializer, this as T)
    }
}
