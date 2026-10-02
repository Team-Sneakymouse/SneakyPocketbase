package com.danidipp.sneakypocketbase

import org.bukkit.command.Command
import org.bukkit.command.CommandSender

class ReloadCommand: Command("reload") {
    init {
        description = "Reloads the plugin configuration"
        usageMessage = "/reload"
        permission = "sneakypocketbase.reload"
    }
    override fun execute(sender: CommandSender, commandLabel: String, args: Array<String>): Boolean {
        val plugin = SneakyPocketbase.getInstance()
        plugin.reloadConfig()
        val restarted = plugin.restartPocketbase()
        plugin.loadConfig()
        if (!restarted) {
            sender.sendMessage("Reloaded config, but Pocketbase restart failed. Check server logs.")
            return true
        }
        val variableStatus = MSVariableSync.statusSnapshot()
        sender.sendMessage("Reloaded config. MagicSpells variable sync: ${variableStatus.configuredCount} configured, ${variableStatus.activeCount} active, ${variableStatus.unsupportedCount} unsupported.")
        return true
    }
}
