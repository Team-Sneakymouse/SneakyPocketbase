package com.danidipp.sneakypocketbase

import kotlinx.coroutines.isActive
import org.bukkit.command.Command
import org.bukkit.command.CommandSender

class StatusCommand : Command("status"){
    init {
        description = "Displays status information"
        usageMessage = "/status"
        permission = "sneakypocketbase.status"
    }
    override fun execute(sender: CommandSender, commandLabel: String, args: Array<String>): Boolean {
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
}