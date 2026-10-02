package com.danidipp.sneakypocketbase

import kotlinx.coroutines.isActive
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import java.time.Instant

class StatusCommand : Command("status"){
    init {
        description = "Displays status information"
        usageMessage = "/status [variables]"
        permission = "sneakypocketbase.status"
    }
    override fun execute(sender: CommandSender, commandLabel: String, args: Array<String>): Boolean {
        if (args.firstOrNull()?.equals("variables", ignoreCase = true) == true) {
            sendVariableStatus(sender)
            return true
        }

        val plugin = SneakyPocketbase.getInstance()
        sender.sendMessage("Plugin enabled: " + plugin.isEnabled)
        val lifecycle = plugin.api().lifecycleSnapshot
        sender.sendMessage("Lifecycle: API=${lifecycle.apiState}, realtime=${lifecycle.transportState}, generation=${lifecycle.generation}, revision=${lifecycle.revision}")
        lifecycle.collections.forEach { (collection, status) ->
            sender.sendMessage("Subscription $collection: ${status.state()} (${status.reason()})")
        }
        if (plugin.hasPocketbaseHandler()) {
            val pbHandler = plugin.pbHandler
            sender.sendMessage("isAuthenticated: " + pbHandler.isAuthenticated)
            sender.sendMessage("isConnected: " + pbHandler.isConnected)
            sender.sendMessage("status: " + pbHandler.status)
        } else {
            sender.sendMessage("Pocketbase handler: Not initialized")
        }
        sender.sendMessage("PBScope: " + if (SneakyPocketbase.asyncScope.isActive) "Active" else "Inactive")
        return true
    }

    private fun sendVariableStatus(sender: CommandSender) {
        val snapshot = MSVariableSync.statusSnapshot()
        sender.sendMessage(
            "MagicSpells variable sync: ${snapshot.configuredCount} configured, " +
                "${snapshot.activeCount} active, ${snapshot.unsupportedCount} unsupported"
        )
        sender.sendMessage("Timer: " + if (snapshot.timerRunning) "Running" else "Stopped")
        sender.sendMessage("Realtime listener: " + if (snapshot.realtimeListenerRegistered) "Registered" else "Stopped")
        sender.sendMessage("Realtime subscription desired: " + snapshot.realtimeSubscriptionDesired)
        sender.sendMessage("PUSH in flight: " + snapshot.pushInFlight)

        if (snapshot.variables.isEmpty()) {
            sender.sendMessage("No MagicSpells variables configured.")
            return
        }

        for (variable in snapshot.variables) {
            val state = when {
                variable.unsupported -> "unsupported"
                variable.active -> "active"
                else -> "disabled"
            }
            val lastAttempt = variable.lastSyncAttemptMillis?.let { Instant.ofEpochMilli(it).toString() } ?: "never"
            val lastError = variable.lastError ?: "none"
            sender.sendMessage(
                "${variable.name}: type=${variable.syncType}, state=$state, " +
                    "lastAttempt=$lastAttempt, lastError=$lastError, inFlight=${variable.inFlight}"
            )
        }
    }
}
