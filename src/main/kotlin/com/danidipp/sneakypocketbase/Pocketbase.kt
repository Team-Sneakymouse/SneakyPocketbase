package com.danidipp.sneakypocketbase

import org.bukkit.Bukkit
import io.github.agrevster.pocketbaseKotlin.PocketbaseClient
import io.github.agrevster.pocketbaseKotlin.dsl.login
import io.github.agrevster.pocketbaseKotlin.models.Record
import io.github.agrevster.pocketbaseKotlin.services.RealtimeService
import io.ktor.client.plugins.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.serialization.*
import kotlinx.serialization.json.Json
import java.util.logging.Logger

class PocketbaseHandler {
    val pocketbase: PocketbaseClient
    private var authWait: Deferred<Unit>
    private val logger: Logger

    @OptIn(DelicateCoroutinesApi::class)
    constructor(logger: Logger,
                pbProtocol: String,
                pbHost: String,
                pbUser: String,
                pbPassword: String) {
        this.logger = logger
        pocketbase = PocketbaseClient({
            this.protocol = URLProtocol.byName[pbProtocol]!!
            this.host = pbHost
        })
        pocketbase.httpClient.plugin(HttpSend).intercept { request ->
            request.headers.append("User-Agent", "SneakyPocketbase")
            execute(request)
        }

        authWait = GlobalScope.async {
            val token = pocketbase.admins.authWithPassword(pbUser, pbPassword).token
            pocketbase.login { this.token = token }
        }
        logger.fine("Registering pre-init loaded callbacks")
        for (callback in SneakyPocketbase.preInitLoadedCallbacks) {
            onLoaded(callback)
        }
        SneakyPocketbase.preInitLoadedCallbacks.clear()
    }

    fun onLoaded(callback: java.lang.Runnable) {
        authWait.invokeOnCompletion {
            callback.run()
        }
    }

    fun stop() {
        runBlocking {
            logger.info("Disconnecting from Pocketbase Realtime")
            pocketbase.realtime.disconnect()
        }
    }

    fun runRealtime() {
        logger.info("Starting Pocketbase Realtime")
        SneakyPocketbase.asyncScope.launch{
            runCatching {
                authWait.await()
            }.onFailure {
                logger.severe("Failed to authenticate with Pocketbase")
                logger.severe(it.stackTraceToString())
                Bukkit.getPluginManager().disablePlugin(SneakyPocketbase.getInstance())
                return@launch // Abort realtime service
            }

            launch(CoroutineName("Connection")) {
                runCatching {
                    logger.info("Connecting to Pocketbase Realtime")
                    pocketbase.realtime.connect()
                }.onFailure {
                    logger.severe("Failed to connect to Pocketbase Realtime")
                    logger.severe(it.stackTraceToString())
                    Bukkit.getPluginManager().disablePlugin(SneakyPocketbase.getInstance())
                }
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
open class BaseRecord: Record(){
    fun <T: BaseRecord> toJson(serializer: KSerializer<T>): String {
        @Suppress("UNCHECKED_CAST")
        return Json.encodeToString(serializer, this as T)
    }
}