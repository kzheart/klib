package me.kzheart.klib.command.api;
import java.util.Objects;
import java.util.function.Consumer;
import me.kzheart.klib.scope.*;
public final class Commands {
    private final Scope owner;
    public Commands(Scope owner) { this.owner = Objects.requireNonNull(owner, "owner"); }
    public Disposable register(Object... handlers) {
        return owner.requireCapability(CommandCapability.class).registerAnnotated(owner, handlers);
    }
    public CommandRegistration register(String name, Consumer<? super CommandSpec> configure) {
        return owner.command(name, configure);
    }
}
