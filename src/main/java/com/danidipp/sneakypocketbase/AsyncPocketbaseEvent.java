package com.danidipp.sneakypocketbase;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Realtime PocketBase event whose interface contains only JDK and Bukkit types. */
public final class AsyncPocketbaseEvent extends Event {
    public enum Action {
        CONNECT,
        CREATE,
        UPDATE,
        DELETE
    }

    private static final HandlerList HANDLERS = new HandlerList();

    private final Action action;
    private final String collectionName;
    private final String recordJson;

    public AsyncPocketbaseEvent(
        final boolean async,
        final Action action,
        final String collectionName,
        final String recordJson
    ) {
        super(async);
        this.action = action;
        this.collectionName = collectionName;
        this.recordJson = recordJson;
    }

    public Action getAction() {
        return this.action;
    }

    public String getCollectionName() {
        return this.collectionName;
    }

    public String getRecordJson() {
        return this.recordJson;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
