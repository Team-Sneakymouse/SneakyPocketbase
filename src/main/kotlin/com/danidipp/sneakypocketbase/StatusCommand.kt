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
        val pbHandler = plugin.pbHandler
        sender.sendMessage("Plugin enabled: " + plugin.isEnabled)
        sender.sendMessage("isAuthenticated: " + pbHandler.isAuthenticated)
        sender.sendMessage("isConnected: " + pbHandler.isConnected)
        sender.sendMessage("status: " + pbHandler.status)
        sender.sendMessage("PBScope: " + if (SneakyPocketbase.asyncScope.isActive) "Active" else "Inactive")
        sender.sendMessage("Variables: " + MSVariableSync.variables.entries.joinToString(", ") { "${it.key}: ${it.value}" })
        return true
    }
}