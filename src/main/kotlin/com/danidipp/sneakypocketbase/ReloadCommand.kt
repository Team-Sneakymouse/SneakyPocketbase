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
        SneakyPocketbase.getInstance().reloadConfig()
        SneakyPocketbase.getInstance().loadConfig()
        sender.sendMessage("Reloaded: " + MSVariableSync.variables.keys.joinToString(","))
        return true
    }
}