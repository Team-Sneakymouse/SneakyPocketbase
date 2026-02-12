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
    val pocketbase: PocketbaseClient
    private var authWait: Deferred<Unit>
    private val logger: Logger
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
            logger.info("Disconnecting from Pocketbase Realtime")
            pocketbase.realtime.disconnect()
            isConnected = false
        }
        status = "Stopped"
    }

    fun runRealtime() {
        logger.info("Starting Pocketbase Realtime")
        status = "Starting Realtime"
        SneakyPocketbase.asyncScope.launch outer@{
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
                var tries = 5
                var lastTry: Long = System.currentTimeMillis()
                do {
                    // Reset tries if last try was more than 5 minutes ago
                    if (System.currentTimeMillis() - lastTry > 5 * 60 * 1000) tries = 5
                    lastTry = System.currentTimeMillis()

                    runCatching {
                        logger.info("Connecting to Pocketbase Realtime")
                        status = "Connected"
                        isConnected = true
                        pocketbase.realtime.connect()
                        isConnected = false
                        status = "Disconnected"
                    }.onFailure {
                        if (it is PocketbaseException && it.reason.contains("already connected", true)) {
                            pocketbase.realtime.disconnect()
                            tries = 5
                        } else {
                            logger.severe("Failed to connect to Pocketbase Realtime")
                            logger.severe(it.stackTraceToString())
                        }
                    }
                    if (!SneakyPocketbase.asyncScope.isActive) return@launch
                    logger.warning("Disconnected from Pocketbase Realtime. $tries remaining. Reconnecting in 30 seconds...")
                    status = "Waiting to reconnect"
                    delay(30_000)
                } while (--tries > 0)
                logger.severe("Failed to connect to Pocketbase Realtime after 5 tries")
                Bukkit.getPluginManager().disablePlugin(SneakyPocketbase.getInstance())
            }
            launch(CoroutineName("Listener")) {
                delay(500)
                runCatching {
                    var alreadyConnected = false
                    pocketbase.realtime.listen {
                        if (action == RealtimeService.RealtimeActionType.CONNECT) {
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
                    logger.severe("Failed to listen to Pocketbase Realtime")
                    logger.severe(it.stackTraceToString())
                    Bukkit.getPluginManager().disablePlugin(SneakyPocketbase.getInstance())
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