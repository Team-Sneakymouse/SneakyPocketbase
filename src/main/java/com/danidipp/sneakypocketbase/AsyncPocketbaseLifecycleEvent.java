package com.danidipp.sneakypocketbase;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Serialized asynchronous lifecycle notification. Schedule Bukkit work on the main thread. */
public final class AsyncPocketbaseLifecycleEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final PocketbaseLifecycleSnapshot previous;
    private final PocketbaseLifecycleSnapshot current;

    public AsyncPocketbaseLifecycleEvent(PocketbaseLifecycleSnapshot previous, PocketbaseLifecycleSnapshot current) {
        super(true);
        this.previous = previous;
        this.current = current;
    }

    public PocketbaseLifecycleSnapshot getPrevious() { return previous; }
    public PocketbaseLifecycleSnapshot getCurrent() { return current; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
