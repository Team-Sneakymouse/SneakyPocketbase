package com.danidipp.sneakypocketbase

import io.github.agrevster.pocketbaseKotlin.PocketbaseClient
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import kotlinx.coroutines.*
import org.bukkit.scheduler.BukkitTask

class SneakyPocketbase : JavaPlugin() {
    private lateinit var pbHandler: PocketbaseHandler

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

    public fun subscribeAsync(subscriptionName: String) {
        asyncScope.launch {
            subscribe(subscriptionName)
        }
    }
    public suspend fun subscribe(subscriptionName: String) {
        pb().realtime.subscribe(subscriptionName)
    }
    public fun unsubscribeAsync(subscriptionName: String) {
        asyncScope.launch {
            unsubscribe(subscriptionName)
        }
    }
    public suspend fun unsubscribe(subscriptionName: String) {
        pb().realtime.unsubscribe(subscriptionName)
    }



    override fun onLoad() {
        instance = this

        saveDefaultConfig()
        val pbProtocol = config.getString("pocketbase.protocol", "http")!!
        val pbHost = config.getString("pocketbase.host")
        val pbUser = config.getString("pocketbase.user")
        val pbPassword = config.getString("pocketbase.password")

        if (pbHost.isNullOrEmpty() || pbUser.isNullOrEmpty() || pbPassword.isNullOrEmpty()) {
            logger.severe("Missing Pocketbase configuration")
            server.pluginManager.disablePlugin(this)
            return
        }
        pbHandler = PocketbaseHandler(logger, pbProtocol, pbHost, pbUser, pbPassword)
    }
    override fun onEnable() {
        loadConfig()
        Bukkit.getServer().commandMap.registerAll(IDENTIFIER, listOf(
            ReloadCommand()
        ))
        pbHandler.runRealtime()
    }
    fun loadConfig() {
        if (Bukkit.getPluginManager().isPluginEnabled("MagicSpells")) {
            val variableList = config.getStringList("variables")
            if (variableList.isEmpty()) {
                config.set("variables", listOf<String>())
            } else {
                MSVariableSync.unregisterAll()
                variableList.forEach {
                    MSVariableSync.register(it, MSVariableSync.SyncType.PUSH)
                }
            }
        }
    }

    override fun onDisable() {
        logger.info("Disabling SneakyPocketbase")
        asyncScope.cancel()
        MSVariableSync.stopSync()

        logger.info("Shutting down Pocketbase")
        pbHandler.stop()
    }

    companion object {
        const val IDENTIFIER = "sneakypocketbase"
        const val AUTHORS = "Team Sneakymouse"
        const val VERSION = "1.0"
        private lateinit var instance: SneakyPocketbase
        val preInitLoadedCallbacks = mutableListOf<java.lang.Runnable>()

        val asyncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun getInstance(): SneakyPocketbase {
            return instance
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