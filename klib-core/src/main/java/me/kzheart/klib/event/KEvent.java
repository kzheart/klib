package me.kzheart.klib.event;

import org.bukkit.Bukkit;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;

public abstract class KEvent extends Event implements Cancellable {
    private boolean cancelled;

    protected KEvent() {
        super();
    }

    protected KEvent(boolean asynchronous) {
        super(asynchronous);
    }

    public final boolean call() {
        Bukkit.getPluginManager().callEvent(this);
        return !cancelled;
    }

    @Override
    public final boolean isCancelled() {
        return cancelled;
    }

    @Override
    public final void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

}
