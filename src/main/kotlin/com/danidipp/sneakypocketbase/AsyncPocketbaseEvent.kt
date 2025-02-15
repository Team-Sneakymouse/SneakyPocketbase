package com.danidipp.sneakypocketbase

import io.github.agrevster.pocketbaseKotlin.services.RealtimeService
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

class AsyncPocketbaseEvent(
    async: Boolean,
    var action: RealtimeService.RealtimeActionType,
    var collectionName: String,
    var data: RealtimeService.MessageData
): Event(async) {
    override fun getHandlers(): HandlerList {
        return HANDLERS
    }

    companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList {
            return HANDLERS
        }
    }
}