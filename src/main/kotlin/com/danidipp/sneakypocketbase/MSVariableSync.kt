package com.danidipp.sneakypocketbase

import com.nisovin.magicspells.MagicSpells
import com.nisovin.magicspells.variables.variabletypes.GlobalStringVariable
import com.nisovin.magicspells.variables.variabletypes.GlobalVariable
import io.github.agrevster.pocketbaseKotlin.dsl.query.Filter
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.scheduler.BukkitTask
import java.util.concurrent.atomic.AtomicBoolean

class MSVariableSync {
    enum class SyncType {
        PUSH, PULL, BOTH
    }

    data class VariableSyncPolicy(
        val variables: Map<String, SyncType>,
    ) {
        companion object {
            val EMPTY = VariableSyncPolicy(emptyMap())
        }
    }

    data class PolicyParseResult(
        val policy: VariableSyncPolicy,
        val errors: Map<String, String>,
    )

    data class StatusSnapshot(
        val variables: List<VariableStatusSnapshot>,
        val timerRunning: Boolean,
        val realtimeListenerRegistered: Boolean,
        val realtimeSubscriptionDesired: Boolean,
        val pushInFlight: Boolean,
    ) {
        val configuredCount: Int
            get() = variables.size

        val activeCount: Int
            get() = variables.count { it.active }

        val unsupportedCount: Int
            get() = variables.count { it.unsupported }
    }

    data class VariableStatusSnapshot(
        val name: String,
        val syncType: SyncType,
        val active: Boolean,
        val unsupported: Boolean,
        val lastSyncAttemptMillis: Long?,
        val lastError: String?,
        val inFlight: Boolean,
    )

    @Serializable
    data class MagicSpellsRecord(
        val variable: String,
        val uuid: String,
        var value: String,
    ): BaseRecord()

    companion object {
        private const val SHUTDOWN_UNSUBSCRIBE_TIMEOUT_MS = 1_000L
        private const val COLLECTION_NAME = "lom2_magicspells"

        private val registryLock = Any()
        private val lifecycleLock = Any()
        private val variableRegistry = linkedMapOf<String, VariableState>()
        private val pushInProgress = AtomicBoolean(false)
        private var syncTask: BukkitTask? = null
        private var listenerRegistered = false
        private var realtimeSubscriptionDesired = false
        private var realtimeSubscriptionGeneration = 0

        private data class PendingPush(
            val name: String,
            val value: String,
        )

        private data class VariableState(
            val syncType: SyncType,
            var active: Boolean,
            val unsupported: Boolean,
            var lastSyncAttemptMillis: Long? = null,
            var lastError: String? = null,
        )

        private fun logFailure(message: String, throwable: Throwable? = null) {
            val logger = SneakyPocketbase.getInstance().logger
            logger.severe(message)
            throwable?.let { logger.severe(it.stackTraceToString()) }
        }

        private fun disableVariableUntilReload(name: String, reason: String) {
            markDisabled(name, reason)
            SneakyPocketbase.getInstance().logger.severe("Disabled MagicSpells sync for variable $name until reload: $reason")
            reconcileLifecycle()
        }

        private fun activeVariableSnapshot(): Map<String, SyncType> =
            synchronized(registryLock) {
                variableRegistry
                    .filterValues { it.active }
                    .mapValues { it.value.syncType }
            }

        private fun variableType(name: String): SyncType? =
            synchronized(registryLock) { variableRegistry[name]?.takeIf { it.active }?.syncType }

        private fun markAttempt(name: String) {
            synchronized(registryLock) {
                variableRegistry[name]?.lastSyncAttemptMillis = System.currentTimeMillis()
            }
        }

        private fun markSuccess(name: String) {
            synchronized(registryLock) {
                variableRegistry[name]?.lastError = null
            }
        }

        private fun markError(name: String, error: String) {
            synchronized(registryLock) {
                variableRegistry[name]?.lastError = error
            }
        }

        private fun markDisabled(name: String, error: String) {
            synchronized(registryLock) {
                variableRegistry[name]?.let {
                    it.active = false
                    it.lastError = error
                }
            }
        }

        private val sync = Runnable {
            val plugin = SneakyPocketbase.getInstance()
            val variableSnapshot = activeVariableSnapshot()
            val hasPushVariables = variableSnapshot.any { (_, type) -> type == SyncType.PUSH }
            if (hasPushVariables && !pushInProgress.compareAndSet(false, true)) {
                plugin.logger.fine("Skipping MagicSpells PUSH sync tick because the previous PUSH sync is still running")
                return@Runnable
            }

            val pendingPushes = runCatching<List<PendingPush>?> {
                if (!MagicSpells.isLoaded()) {
                    plugin.logger.warning("MagicSpells is not loaded. Skipping variable sync")
                    variableSnapshot
                        .filterValues { it == SyncType.PUSH }
                        .keys
                        .forEach { name ->
                            markAttempt(name)
                            markError(name, "MagicSpells is not loaded")
                        }
                    null
                } else {
                    val variableManager = MagicSpells.getVariableManager()
                    if (variableManager == null) {
                        plugin.logger.severe("MagicSpells variable manager is null")
                        variableSnapshot
                            .filterValues { it == SyncType.PUSH }
                            .keys
                            .forEach { name ->
                                markAttempt(name)
                                markError(name, "MagicSpells variable manager is null")
                            }
                        null
                    } else {
                        variableSnapshot.mapNotNull { (name, type) ->
                            if (type != SyncType.PUSH) return@mapNotNull null
                            markAttempt(name)

                            val variable = variableManager.getVariable(name)
                            if (variable == null) {
                                plugin.logger.severe("Failed to get variable $name")
                                markError(name, "MagicSpells variable not found")
                                return@mapNotNull null
                            }

                            if (variable !is GlobalVariable && variable !is GlobalStringVariable) {
                                plugin.logger.severe("Variable $name is not a global variable")
                                markError(name, "MagicSpells variable is not global")
                                return@mapNotNull null
                            }

                            PendingPush(name, variable.getStringValue("null"))
                        }
                    }
                }
            }.onFailure {
                logFailure("Failed to read MagicSpells variables for sync", it)
            }.getOrNull() ?: run {
                if (hasPushVariables) pushInProgress.set(false)
                return@Runnable
            }

            if (hasPushVariables) {
                SneakyPocketbase.asyncScope.launch {
                    try {
                        for ((name, value) in pendingPushes) {
                            runCatching {
                                val recordList = plugin.pb().records.getFullList<MagicSpellsRecord>(
                                    COLLECTION_NAME,
                                    1,
                                    filterBy = Filter("variable=\"$name\" && uuid=\"\"")
                                )

                                val record = recordList.firstOrNull()
                                if (record == null) {
                                    logFailure("Failed to find record for variable $name (size ${recordList.size})")
                                    markError(name, "Pocketbase record not found")
                                    return@runCatching
                                }

                                if (record.value == value) {
                                    markSuccess(name)
                                    return@runCatching
                                }

                                record.value = value
                                plugin.pb().records.update<MagicSpellsRecord>(
                                    COLLECTION_NAME,
                                    record.id!!,
                                    record.toJson(MagicSpellsRecord.serializer())
                                )
                                markSuccess(name)
                            }.onFailure {
                                markError(name, it.message ?: it::class.java.simpleName)
                                logFailure("Failed to push variable $name", it)
                            }
                        }
                    } finally {
                        pushInProgress.set(false)
                    }
                }
            }

            for ((_, type) in variableSnapshot) {
                if (type == SyncType.PULL) {
                    // Pull the variable from the db
                    // Implemented in realtime
                }
            }
        }
        private val listener = object : Listener {
            @EventHandler
            fun onRecordUpdate(event: AsyncPocketbaseEvent) {
                if (event.collectionName != COLLECTION_NAME) return
                val record = runCatching {
                    kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                        .decodeFromString<MagicSpellsRecord>(event.recordJson)
                }.onFailure {
                    logFailure("Failed to parse $COLLECTION_NAME event", it)
                }.getOrNull() ?: return

                val plugin = SneakyPocketbase.getInstance()
                plugin.logger.info("Received $COLLECTION_NAME event: ${record.variable} = ${record.value}")

                Bukkit.getScheduler().runTask(plugin, Runnable {
                    runCatching {
                        val type = variableType(record.variable) ?: return@Runnable
                        if (type != SyncType.PULL) return@Runnable
                        markAttempt(record.variable)
                        if (!Bukkit.getPluginManager().isPluginEnabled("MagicSpells")) return@Runnable
                        if (!MagicSpells.isLoaded()) {
                            markError(record.variable, "MagicSpells is not loaded")
                            return@Runnable
                        }

                        val variableManager = MagicSpells.getVariableManager()
                        if (variableManager == null) {
                            markError(record.variable, "MagicSpells variable manager is null")
                            logFailure("MagicSpells variable manager is null")
                            return@Runnable
                        }

                        val variable = variableManager.getVariable(record.variable)
                        if (variable == null) {
                            markError(record.variable, "MagicSpells variable not found")
                            logFailure("Couldn't update variable ${record.variable}: variable not found")
                            return@Runnable
                        }

                        if (variable is GlobalStringVariable) {
                            variable.parseAndSet("null", record.value)
                            markSuccess(record.variable)
                            return@Runnable
                        }

                        val value = record.value.toDoubleOrNull()
                        if (value == null) {
                            disableVariableUntilReload(record.variable, "remote value '${record.value}' is not numeric")
                            return@Runnable
                        }

                        variable.set("null", value)
                        markSuccess(record.variable)
                    }.onFailure {
                        markError(record.variable, it.message ?: it::class.java.simpleName)
                        logFailure("Failed to apply MagicSpells variable update for ${record.variable}", it)
                    }
                })
            }
        }

        fun parsePolicy(configuredVariables: Map<String, String?>): PolicyParseResult {
            val variables = linkedMapOf<String, SyncType>()
            val errors = linkedMapOf<String, String>()

            for ((name, value) in configuredVariables) {
                if (value.isNullOrBlank()) {
                    errors[name] = "Missing sync type"
                    continue
                }

                val syncType = runCatching { SyncType.valueOf(value.uppercase()) }.getOrNull()
                if (syncType == null) {
                    errors[name] = "Unsupported sync type '$value'"
                    continue
                }

                variables[name] = syncType
            }

            return PolicyParseResult(VariableSyncPolicy(variables), errors)
        }

        fun applyPolicy(policy: VariableSyncPolicy) {
            val plugin = SneakyPocketbase.getInstance()
            synchronized(registryLock) {
                variableRegistry.clear()
                for ((name, syncType) in policy.variables) {
                    val unsupported = syncType == SyncType.BOTH
                    variableRegistry[name] = VariableState(
                        syncType = syncType,
                        active = !unsupported,
                        unsupported = unsupported,
                        lastError = if (unsupported) "BOTH sync is unsupported" else null,
                    )
                    if (unsupported) {
                        plugin.logger.warning("MagicSpells variable sync BOTH is unsupported for $name; sync is disabled for this variable")
                    } else {
                        plugin.logger.info("Configured MagicSpells $syncType sync for variable $name")
                    }
                }
            }
            reconcileLifecycle()
        }

        fun statusSnapshot(): StatusSnapshot {
            val variableSnapshots = synchronized(registryLock) {
                variableRegistry
                    .map { (name, state) ->
                        VariableStatusSnapshot(
                            name = name,
                            syncType = state.syncType,
                            active = state.active,
                            unsupported = state.unsupported,
                            lastSyncAttemptMillis = state.lastSyncAttemptMillis,
                            lastError = state.lastError,
                            inFlight = state.active && state.syncType == SyncType.PUSH && pushInProgress.get(),
                        )
                    }
            }
            val lifecycle = synchronized(lifecycleLock) {
                Triple(syncTask != null, listenerRegistered, realtimeSubscriptionDesired)
            }

            return StatusSnapshot(
                variables = variableSnapshots,
                timerRunning = lifecycle.first,
                realtimeListenerRegistered = lifecycle.second,
                realtimeSubscriptionDesired = lifecycle.third,
                pushInFlight = pushInProgress.get(),
            )
        }

        private fun reconcileLifecycle() {
            val activeVariables = activeVariableSnapshot()
            if (activeVariables.any { (_, type) -> type == SyncType.PUSH }) {
                startTimer()
            } else {
                stopTimer()
            }

            if (activeVariables.any { (_, type) -> type == SyncType.PULL }) {
                ensureRealtimeSubscription()
            } else {
                stopRealtimeSubscription()
            }
        }

        private fun startTimer() {
            synchronized(lifecycleLock) {
                if (syncTask != null) return
            }
            val task = Bukkit.getScheduler().runTaskTimer(SneakyPocketbase.getInstance(), sync, 0, 20 * 15)
            synchronized(lifecycleLock) {
                if (syncTask == null) {
                    syncTask = task
                } else {
                    task.cancel()
                }
            }
        }

        private fun stopTimer() {
            val task = synchronized(lifecycleLock) {
                syncTask.also { syncTask = null }
            }
            task?.cancel()
        }

        private fun ensureRealtimeSubscription() {
            val plugin = SneakyPocketbase.getInstance()
            val generation = synchronized(lifecycleLock) {
                if (!listenerRegistered) {
                    Bukkit.getPluginManager().registerEvents(listener, plugin)
                    listenerRegistered = true
                }
                realtimeSubscriptionDesired = true
                realtimeSubscriptionGeneration += 1
                realtimeSubscriptionGeneration
            }

            plugin.onPocketbaseLoaded {
                if (!shouldKeepRealtimeSubscription(generation)) return@onPocketbaseLoaded
                plugin.logger.info("Subscribing to $COLLECTION_NAME for variable sync")
                plugin.subscribeAsync(COLLECTION_NAME)
            }
        }

        private fun shouldKeepRealtimeSubscription(generation: Int): Boolean =
            synchronized(lifecycleLock) {
                realtimeSubscriptionDesired && realtimeSubscriptionGeneration == generation
            }

        private fun stopRealtimeSubscription() {
            val shouldUnsubscribe = synchronized(lifecycleLock) {
                val wasDesired = realtimeSubscriptionDesired
                realtimeSubscriptionDesired = false
                realtimeSubscriptionGeneration += 1
                wasDesired
            }

            HandlerList.unregisterAll(listener)
            synchronized(lifecycleLock) {
                listenerRegistered = false
            }

            val plugin = SneakyPocketbase.getInstance()
            if (!shouldUnsubscribe || !plugin.hasPocketbaseHandler()) return

            runBlocking {
                val unsubscribeJob = SneakyPocketbase.asyncScope.async(CoroutineName("MagicSpellsVariableSyncUnsubscribe")) {
                    plugin.unsubscribe(COLLECTION_NAME)
                }
                runCatching {
                    val completed = withTimeoutOrNull(SHUTDOWN_UNSUBSCRIBE_TIMEOUT_MS) {
                        unsubscribeJob.await()
                        true
                    } ?: false
                    if (!completed) {
                        plugin.logger.warning("Timed out after ${SHUTDOWN_UNSUBSCRIBE_TIMEOUT_MS}ms while unsubscribing from $COLLECTION_NAME during shutdown")
                        unsubscribeJob.cancel()
                    }
                }.onFailure {
                    plugin.logger.warning("Failed to unsubscribe from $COLLECTION_NAME during shutdown")
                    plugin.logger.fine(it.stackTraceToString())
                }
            }
        }

        fun stopSync() {
            synchronized(registryLock) {
                variableRegistry.clear()
            }
            stopTimer()
            stopRealtimeSubscription()
        }
    }
}
