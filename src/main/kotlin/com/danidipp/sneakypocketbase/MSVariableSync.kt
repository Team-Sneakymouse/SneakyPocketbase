package com.danidipp.sneakypocketbase

import com.nisovin.magicspells.MagicSpells
import com.nisovin.magicspells.variables.variabletypes.GlobalStringVariable
import com.nisovin.magicspells.variables.variabletypes.GlobalVariable
import io.github.agrevster.pocketbaseKotlin.dsl.query.Filter
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.bukkit.Bukkit
import org.bukkit.scheduler.BukkitTask

class MSVariableSync {
    enum class SyncType {
        PUSH, PULL, BOTH
    }

    @Serializable
    data class MagicSpellsRecord(
        val variable: String,
        val uuid: String,
        var value: String,
    ): BaseRecord()

    companion object {
        val variables = mutableMapOf<String, SyncType>()
        private var syncTask: BukkitTask? = null

        private val sync = Runnable {
            if (!MagicSpells.isLoaded()) {
                SneakyPocketbase.getInstance().logger.warning("MagicSpells is not loaded. Skipping variable sync")
                return@Runnable
            }
            for ((name, type) in variables) {
                if (type == SyncType.PUSH) {
                    SneakyPocketbase.asyncScope.launch {
                        val result = runCatching {
                            SneakyPocketbase.getInstance().pb().records.getFullList<MagicSpellsRecord>(
                                "lom2_magicspells",
                                1,
                                filterBy = Filter("variable=\"$name\" && uuid=\"\"")
                            )
                        }
                        if (result.isFailure) {
                            SneakyPocketbase.getInstance().logger.severe("Failed to check variable $name")
                            SneakyPocketbase.getInstance().logger.severe(result.exceptionOrNull()?.stackTraceToString())
                            return@launch
                        }

                        val recordList = result.getOrNull()
                        if (recordList == null) {
                            SneakyPocketbase.getInstance().logger.severe("Pocketbase returned null result!")
                            return@launch
                        }

                        val record = recordList.firstOrNull()
                        if (record == null) {
                            SneakyPocketbase.getInstance().logger.severe("Failed to find record for variable $name (size ${recordList.size})")
                            return@launch
                        }

                        val variableManager = MagicSpells.getVariableManager()
                        if (variableManager == null) {
                            SneakyPocketbase.getInstance().logger.severe("MagicSpells variable manager is null")
                            return@launch
                        }

                        val variable = variableManager.getVariable(name)
                        if (variable == null) {
                            SneakyPocketbase.getInstance().logger.severe("Failed to get variable $name")
                            return@launch
                        }
                        if (variable is GlobalVariable || variable is GlobalStringVariable) {
                            val newValue = variable.getStringValue("null")
                            if (record.value == newValue) return@launch
                            record.value = newValue
                        } else {
                            SneakyPocketbase.getInstance().logger.severe("Variable $name is not a global variable")
                            return@launch
                        }

                        val result2 = runCatching {
                            SneakyPocketbase.getInstance().pb().records.update<MagicSpellsRecord>(
                                "lom2_magicspells",
                                record.id!!,
                                record.toJson(MagicSpellsRecord.serializer())
                            )
                        }
                        if (result2.isFailure) {
                            SneakyPocketbase.getInstance().logger.severe("Failed to push variable $name")
                            SneakyPocketbase.getInstance().logger.severe(result2.exceptionOrNull()?.stackTraceToString())
                            return@launch
                        }
                    }
                }

                if (type == SyncType.PULL) {
                    // Pull the variable from the db
                    // TODO: Move to realtime
                }

                if (type == SyncType.BOTH) {
                    // Push and pull the variable
                    // TODO: Move to realtime
                }
            }
        }
        private fun startSync() {
            if (syncTask != null) return
            syncTask = Bukkit.getScheduler().runTaskTimer(SneakyPocketbase.getInstance(), sync, 0, 20 * 15)
        }
        fun stopSync() {
            syncTask?.cancel()
            syncTask = null
        }

        fun register(name: String, type: SyncType) {
            SneakyPocketbase.getInstance().logger.info("Registering $type on variable $name")
            variables[name] = type
            if (syncTask == null) startSync()
        }

        fun unregister(name: String) {
            variables.remove(name)
            if (variables.isEmpty()) stopSync()
        }
        fun unregisterAll() {
            variables.clear()
            stopSync()
        }
    }
}