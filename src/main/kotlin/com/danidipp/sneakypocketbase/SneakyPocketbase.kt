package com.danidipp.sneakypocketbase

import io.github.agrevster.pocketbaseKotlin.PocketbaseClient
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import kotlinx.coroutines.*

class SneakyPocketbase : JavaPlugin() {
    internal lateinit var pbHandler: PocketbaseHandler
    internal val lifecycle = PocketbaseLifecycle(
        deliver = { previous, current -> Bukkit.getPluginManager().callEvent(AsyncPocketbaseLifecycleEvent(previous, current)) },
        deliveryFailed = { logger.warning("PocketBase lifecycle listener failed: ${it.javaClass.simpleName}") },
    )
    private val consumerApi: PocketbaseApi = PocketbaseApiAdapter(
        scope = { asyncScope },
        client = { pb() },
        ready = { onPocketbaseLoaded(it) },
        subscribeAction = { subscribe(it) },
        unsubscribeAction = { unsubscribe(it) },
        lifecycle = { lifecycle.snapshot() },
        operationFailed = { generation, failure ->
            if (::pbHandler.isInitialized) pbHandler.requestFailed(generation, failure)
        },
    )

    fun hasPocketbaseHandler(): Boolean {
        return ::pbHandler.isInitialized
    }

    internal fun pb(): PocketbaseClient {
        if (::pbHandler.isInitialized) {
            return pbHandler.pocketbase
        } else {
            throw IllegalStateException("Pocketbase not loaded yet")
        }
    }

    /**
     * Registers a callback that runs after Pocketbase authentication completes.
     *
     * This is a Pocketbase callback, not a Bukkit callback: callbacks are always
     * scheduled on [asyncScope]. Callers that touch Bukkit or other main-thread
     * APIs must switch to the Bukkit main thread themselves.
     */
    fun onPocketbaseLoaded(callback: java.lang.Runnable) {
        if (::pbHandler.isInitialized) {
            logger.fine("Pocketbase handler initialized. Registering callback on Pocketbase async scope.")
            pbHandler.onLoaded(callback)
        } else {
            logger.fine("Pocketbase handler not initialized yet. Registering callback for later.")
            addPreInitLoadedCallback(callback)
        }
    }

    fun api(): PocketbaseApi = consumerApi

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
    internal suspend fun subscribe(subscriptionName: String) {
        lifecycle.register(subscriptionName)
        if (::pbHandler.isInitialized) pbHandler.subscriptionsChanged()
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
    internal suspend fun unsubscribe(subscriptionName: String) {
        lifecycle.unregister(subscriptionName)
        if (::pbHandler.isInitialized) pbHandler.subscriptionsChanged()
    }

    override fun onLoad() {
        logger.info("Loading SneakyPocketbase")
        instance = this
        PocketbaseProvider.install(consumerApi)
        resetAsyncScope()

        saveDefaultConfig()
        if (!initializePocketbase()) {
            server.pluginManager.disablePlugin(this)
            return
        }
        logger.info("SneakyPocketbase loaded")
    }
    override fun onEnable() {
        if (!isEnabled || !::pbHandler.isInitialized) {
            logger.warning("Plugin is disabled. Skipping onEnable.")
            return
        }
        loadConfig()
        lifecycle.startDelivery()
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
            val configuredVariables = if (configVariableSection == null || configVariables.isEmpty()) {
                emptyMap()
            } else {
                configVariables.associateWith { configVariableSection.getString(it) }
            }

            val parseResult = MSVariableSync.parsePolicy(configuredVariables)
            for ((variable, error) in parseResult.errors) {
                logger.warning("Ignoring MagicSpells variable sync config for $variable: $error")
            }
            MSVariableSync.applyPolicy(parseResult.policy)
        } else {
            MSVariableSync.applyPolicy(MSVariableSync.VariableSyncPolicy.EMPTY)
        }
    }

    fun restartPocketbase(): Boolean {
        val settings = readPocketbaseSettings() ?: return false
        val owner = lifecycle.beginHandler()

        if (::pbHandler.isInitialized) {
            logger.info("Restarting Pocketbase")
            pbHandler.stop()
        }

        pbHandler = createPocketbaseHandler(settings, owner)
        pbHandler.runRealtime()
        return true
    }

    private fun initializePocketbase(): Boolean {
        val settings = readPocketbaseSettings() ?: return false
        pbHandler = createPocketbaseHandler(settings, lifecycle.beginHandler())
        return true
    }

    private fun readPocketbaseSettings(): PocketbaseSettings? {
        val pbProtocol = config.getString("pocketbase.protocol", "http")!!
        val pbHost = config.getString("pocketbase.host")
        val pbUser = config.getString("pocketbase.user")
        val pbPassword = config.getString("pocketbase.password")
        val serverName = config.getString("serverName", null)?.ifEmpty { null }

        if (pbHost.isNullOrEmpty() || pbUser.isNullOrEmpty() || pbPassword.isNullOrEmpty()) {
            logger.severe("Missing Pocketbase configuration")
            return null
        }

        return PocketbaseSettings(pbProtocol, pbHost, pbUser, pbPassword, serverName)
    }

    private fun createPocketbaseHandler(settings: PocketbaseSettings, owner: Long): PocketbaseHandler {
        val handler = PocketbaseHandler(
            logger,
            settings.protocol,
            settings.host,
            settings.user,
            settings.password,
            settings.serverName,
            lifecycle,
            owner,
        )
        drainPreInitLoadedCallbacks().forEach(handler::onLoaded)
        return handler
    }

    override fun onDisable() {
        lifecycle.stop()
        stopPocketbaseLoadedCallbacks()
        logger.info("Disabling SneakyPocketbase")
        MSVariableSync.stopSync()

        if (::pbHandler.isInitialized) {
            logger.info("Shutting down Pocketbase")
            pbHandler.stop()
        }

        shutdownAsyncScope()
        lifecycle.finishDelivery()
        PocketbaseProvider.clear()
    }

    companion object {
        const val IDENTIFIER = "sneakypocketbase"
        const val AUTHORS = "Team Sneakymouse"
        const val VERSION = "1.0"
        private lateinit var instance: SneakyPocketbase
        private val preInitLoadedCallbacks = mutableListOf<java.lang.Runnable>()
        private var asyncScopeRef: CoroutineScope? = null
        @Volatile
        private var acceptingPocketbaseLoadedCallbacks = false

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
            acceptingPocketbaseLoadedCallbacks = true
        }

        fun shutdownAsyncScope() {
            acceptingPocketbaseLoadedCallbacks = false
            asyncScopeRef?.cancel()
            asyncScopeRef = null
        }

        fun stopPocketbaseLoadedCallbacks() {
            acceptingPocketbaseLoadedCallbacks = false
        }

        fun pocketbaseLoadedCallbackScopeOrNull(): CoroutineScope? {
            return asyncScopeRef?.takeIf { acceptingPocketbaseLoadedCallbacks && it.isActive }
        }

        fun addPreInitLoadedCallback(callback: java.lang.Runnable) {
            synchronized(preInitLoadedCallbacks) {
                preInitLoadedCallbacks.add(callback)
            }
        }

        fun drainPreInitLoadedCallbacks(): List<java.lang.Runnable> {
            return synchronized(preInitLoadedCallbacks) {
                preInitLoadedCallbacks.toList().also {
                    preInitLoadedCallbacks.clear()
                }
            }
        }
    }

    private data class PocketbaseSettings(
        val protocol: String,
        val host: String,
        val user: String,
        val password: String,
        val serverName: String?,
    )
}
