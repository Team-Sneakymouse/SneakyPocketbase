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
import java.util.logging.Logger

class PocketbaseHandler {
    companion object {
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val RECONNECT_DELAY_MS = 30_000L
        private const val RETRY_RESET_WINDOW_MS = 5 * 60 * 1000L
        private const val LISTENER_RETRY_DELAY_MS = 1_000L
    }

    val pocketbase: PocketbaseClient
    private var authWait: Deferred<Unit>
    private val logger: Logger
    private var realtimeJob: Job? = null
    var isConnected: Boolean = false
    var isAuthenticated: Boolean = false
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
        authWait = GlobalScope.async {
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
        authWait.invokeOnCompletion {
            callback.run()
        }
    }

    fun stop() {
        status = "Stopping"
        runBlocking {
            realtimeJob?.cancelAndJoin()
            realtimeJob = null

            logger.info("Disconnecting from Pocketbase Realtime")
            runCatching {
                pocketbase.realtime.disconnect()
            }.onFailure {
                if (it !is CancellationException) {
                    logger.warning("Failed to disconnect from Pocketbase Realtime cleanly")
                    logger.fine(it.stackTraceToString())
                }
            }
            isConnected = false
        }
        status = "Stopped"
    }

    fun runRealtime() {
        logger.info("Starting Pocketbase Realtime")
        status = "Starting Realtime"
        realtimeJob?.cancel()
        realtimeJob = SneakyPocketbase.asyncScope.launch outer@{
            runCatching {
                status = "Waiting for authentication"
                authWait.await()
                status = "Authenticated"
            }.onFailure {
                status = "Authentication failed"
                logger.severe("Failed to authenticate with Pocketbase")
                logger.severe(it.stackTraceToString())
                Bukkit.getPluginManager().disablePlugin(SneakyPocketbase.getInstance())
                return@outer // Abort realtime service
            }

            launch(CoroutineName("Connection")) {
                var triesRemaining = MAX_RECONNECT_ATTEMPTS
                var lastTry = 0L

                while (isActive) {
                    // Reset tries if last try was more than 5 minutes ago
                    if (lastTry != 0L && System.currentTimeMillis() - lastTry > RETRY_RESET_WINDOW_MS) {
                        triesRemaining = MAX_RECONNECT_ATTEMPTS
                    }
                    lastTry = System.currentTimeMillis()

                    runCatching {
                        logger.info("Connecting to Pocketbase Realtime")
                        status = "Connecting"
                        pocketbase.realtime.connect()
                    }.onFailure {
                        if (it is PocketbaseException && it.reason.contains("already connected", true)) {
                            runCatching {
                                pocketbase.realtime.disconnect()
                            }.onFailure { disconnectFailure ->
                                logger.warning("Failed to reset Pocketbase Realtime after duplicate connection")
                                logger.fine(disconnectFailure.stackTraceToString())
                            }
                            triesRemaining = MAX_RECONNECT_ATTEMPTS
                        } else {
                            logger.severe("Failed to connect to Pocketbase Realtime")
                            logger.severe(it.stackTraceToString())
                        }
                    }

                    isConnected = false
                    status = "Disconnected"

                    if (!isActive || !SneakyPocketbase.asyncScope.isActive) return@launch
                    if (triesRemaining <= 0) break

                    logger.warning("Disconnected from Pocketbase Realtime. $triesRemaining remaining. Reconnecting in 30 seconds...")
                    status = "Waiting to reconnect"
                    triesRemaining--
                    delay(RECONNECT_DELAY_MS)
                }

                logger.severe("Failed to connect to Pocketbase Realtime after $MAX_RECONNECT_ATTEMPTS tries")
                Bukkit.getPluginManager().disablePlugin(SneakyPocketbase.getInstance())
            }
            launch(CoroutineName("Listener")) {
                delay(LISTENER_RETRY_DELAY_MS / 2)
                var alreadyConnected = false

                while (isActive) {
                    runCatching {
                        pocketbase.realtime.listen {
                            if (action == RealtimeService.RealtimeActionType.CONNECT) {
                                isConnected = true
                                status = "Connected"
                                if (!alreadyConnected) {
                                    alreadyConnected = true
                                    logger.info("Connected to Pocketbase Realtime")
                                } else {
                                    logger.fine("Reconnected to Pocketbase Realtime")
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
                    }.onFailure {
                        isConnected = false
                        status = "Disconnected"
                        if (it is CancellationException || !isActive) {
                            return@launch
                        }

                        logger.warning("Pocketbase Realtime listener stopped while the connection is down. Waiting for reconnect...")
                        logger.fine(it.stackTraceToString())
                    }

                    if (!isActive) return@launch
                    delay(LISTENER_RETRY_DELAY_MS)
                }
            }
        }
    }
}

@Serializable
open class BaseRecord(@Transient open val recordId: String? = null): Record(recordId){
    fun <T: BaseRecord> toJson(serializer: KSerializer<T>): String {
        @Suppress("UNCHECKED_CAST")
        return Json.encodeToString(serializer, this as T)
    }
}
