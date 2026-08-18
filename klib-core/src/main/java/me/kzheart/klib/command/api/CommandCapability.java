package me.kzheart.klib.command.api;

import java.util.function.Consumer;
import me.kzheart.klib.scope.Scope;

public interface CommandCapability {
    CommandRegistration register(
            Scope owner,
            String name,
            Consumer<? super CommandSpec> configure);
}
