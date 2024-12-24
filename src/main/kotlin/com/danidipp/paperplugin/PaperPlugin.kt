package com.danidipp.paperplugin

import org.bukkit.plugin.java.JavaPlugin

class PaperPlugin : JavaPlugin() {

    override fun onLoad() {
        instance = this
    }
    override fun onEnable() {
        saveDefaultConfig()
    }

    companion object {
        const val IDENTIFIER = "paperplugin"
        const val AUTHORS = "Team Sneakymouse"
        const val VERSION = "1.0"
        private lateinit var instance: PaperPlugin

        fun getInstance(): PaperPlugin {
            return instance
        }
    }
}
