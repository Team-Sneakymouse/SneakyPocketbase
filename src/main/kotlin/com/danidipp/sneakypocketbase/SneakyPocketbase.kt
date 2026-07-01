package com.danidipp.sneakypocketbase

import io.github.agrevster.pocketbaseKotlin.PocketbaseClient
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import kotlinx.coroutines.*

class SneakyPocketbase : JavaPlugin() {
    lateinit var pbHandler: PocketbaseHandler

    fun hasPocketbaseHandler(): Boolean {
        return ::pbHandler.isInitialized
    }

    fun pb(): PocketbaseClient {
        if (::pbHandler.isInitialized) {
            return pbHandler.pocketbase
        } else {
            throw IllegalStateException("Pocketbase not loaded yet")
        }
    }

    fun onPocketbaseLoaded(callback: java.lang.Runnable) {
        if (::pbHandler.isInitialized) {
            logger.fine("Pocketbase already loaded. Registering callback directly.")
            pbHandler.onLoaded(callback)
        } else {
            val msg = "Pocketbase not loaded yet. Registering callback for later."
            preInitLoadedCallbacks.add(callback)
        }
    }

    fun subscribeAsync(subscriptionName: String) {
        asyncScope.launch {
            runCatching {
                subscribe(subscriptionName)
            }.onFailure {
                logger.warning("Failed to subscribe to Pocketbase Realtime '$subscriptionName'")
                logger.fine(it.stackTraceToString())
            }
        }
    }
    suspend fun subscribe(subscriptionName: String) {
        if (::pbHandler.isInitialized) {
            pbHandler.subscribe(subscriptionName)
        } else {
            throw IllegalStateException("Pocketbase not loaded yet")
        }
    }
    fun unsubscribeAsync(subscriptionName: String) {
        asyncScope.launch {
            runCatching {
                unsubscribe(subscriptionName)
            }.onFailure {
                logger.warning("Failed to unsubscribe from Pocketbase Realtime '$subscriptionName'")
                logger.fine(it.stackTraceToString())
            }
        }
    }
    suspend fun unsubscribe(subscriptionName: String) {
        if (::pbHandler.isInitialized) {
            pbHandler.unsubscribe(subscriptionName)
        } else {
            throw IllegalStateException("Pocketbase not loaded yet")
        }
    }

    override fun onLoad() {
        logger.info("Loading SneakyPocketbase")
        instance = this
        resetAsyncScope()

        saveDefaultConfig()
        val pbProtocol = config.getString("pocketbase.protocol", "http")!!
        val pbHost = config.getString("pocketbase.host")
        val pbUser = config.getString("pocketbase.user")
        val pbPassword = config.getString("pocketbase.password")
        val serverName = config.getString("serverName", null)?.ifEmpty { null }

        if (pbHost.isNullOrEmpty() || pbUser.isNullOrEmpty() || pbPassword.isNullOrEmpty()) {
            logger.severe("Missing Pocketbase configuration")
            server.pluginManager.disablePlugin(this)
            return
        }
        pbHandler = PocketbaseHandler(logger, pbProtocol, pbHost, pbUser, pbPassword, serverName)
        logger.info("SneakyPocketbase loaded")
    }
    override fun onEnable() {
        if (!isEnabled || !::pbHandler.isInitialized) {
            logger.warning("Plugin is disabled. Skipping onEnable.")
            return
        }
        loadConfig()
        Bukkit.getServer().commandMap.registerAll(IDENTIFIER, listOf(
            ReloadCommand(),
            StatusCommand(),
        ))
        pbHandler.runRealtime()
    }
    fun loadConfig() {
        if (Bukkit.getPluginManager().isPluginEnabled("MagicSpells")) {
            val configVariableSection = config.getConfigurationSection("variables")
            val configVariables = configVariableSection?.getKeys(false) ?: emptySet()
            if (configVariableSection == null || configVariables.isEmpty()) {
                MSVariableSync.unregisterAll()
                config.set("variables", listOf<String>())
            } else {
                MSVariableSync.unregisterAll()
                configVariables.forEach {
                    val value = configVariableSection.getString(it) ?: return@forEach
                    MSVariableSync.register(it, MSVariableSync.SyncType.valueOf(value))
                }
            }
            for (variable in MSVariableSync.variables.keys) {
                if (!configVariables.contains(variable)) {
                    MSVariableSync.unregister(variable)
                }
            }
        }
    }

    override fun onDisable() {
        logger.info("Disabling SneakyPocketbase")
        MSVariableSync.stopSync()

        if (::pbHandler.isInitialized) {
            logger.info("Shutting down Pocketbase")
            pbHandler.stop()
        }

        shutdownAsyncScope()
    }

    companion object {
        const val IDENTIFIER = "sneakypocketbase"
        const val AUTHORS = "Team Sneakymouse"
        const val VERSION = "1.0"
        private lateinit var instance: SneakyPocketbase
        val preInitLoadedCallbacks = mutableListOf<java.lang.Runnable>()
        private var asyncScopeRef: CoroutineScope? = null

        val asyncScope: CoroutineScope
            get() = asyncScopeRef ?: throw IllegalStateException("Async scope not initialized")

        fun getInstance(): SneakyPocketbase {
            if(!::instance.isInitialized) {
                throw IllegalStateException("Plugin not initialized yet")
            }
            return instance
        }

        private fun createAsyncScope(): CoroutineScope {
            return CoroutineScope(SupervisorJob() + Dispatchers.IO)
        }

        fun resetAsyncScope() {
            asyncScopeRef?.cancel()
            asyncScopeRef = createAsyncScope()
        }

        fun shutdownAsyncScope() {
            asyncScopeRef?.cancel()
            asyncScopeRef = null
        }
    }
}

/**
 * Makes a coroutine runnable in a Bukkit async task.
 * Usage:
 * ```
 * val task = Bukkit.getScheduler().runTaskAsynchronously(plugin, PBRunnable(scope) {
 *     // Your code here
 * })
 * ```
 */
class PBRunnable(
    private val scope: CoroutineScope = SneakyPocketbase.asyncScope,
    private val coroutine: suspend CoroutineScope.() -> Unit
) : java.lang.Runnable {
    override fun run() {
        scope.launch {
            coroutine()
        }
    }
}
