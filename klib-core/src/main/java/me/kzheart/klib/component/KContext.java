package me.kzheart.klib.component;

import java.util.Objects;
import me.kzheart.klib.scope.Scope;
import me.kzheart.klib.command.api.Commands;
import me.kzheart.klib.config.api.Configs;
import me.kzheart.klib.event.Events;
import me.kzheart.klib.scheduler.Tasks;

/** Instance-bound services; never a global or thread-local current scope. */
public final class KContext {
    private final Scope owner;
    public KContext(Scope owner) { this.owner = Objects.requireNonNull(owner, "owner"); }
    public Scope scope() { return owner; }
    public Commands commands() { return new Commands(owner); }
    public Configs configs() { return new Configs(owner); }
    public Events events() { return new Events(owner); }
    public Tasks tasks() { return new Tasks(owner); }
    public Components components() { return new Components(owner); }
}
