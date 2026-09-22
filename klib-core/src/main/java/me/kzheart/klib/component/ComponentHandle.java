package me.kzheart.klib.component;
import me.kzheart.klib.scope.*;
public final class ComponentHandle implements Disposable, AutoCloseable {
    private final Scope owner;
    ComponentHandle(Scope owner) { this.owner = owner; }
    public boolean isClosed() { return owner.isClosed(); }
    @Override public void close() { owner.close(); }
    @Override public void dispose() { close(); }
}
