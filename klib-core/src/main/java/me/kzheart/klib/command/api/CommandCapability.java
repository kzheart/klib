package me.kzheart.klib.command.api;

import java.util.function.Consumer;

import me.kzheart.klib.scope.Disposable;
import me.kzheart.klib.scope.Scope;

public interface CommandCapability {
    default Disposable registerAnnotated(Scope owner, Object... handlers) {
        throw new UnsupportedOperationException("This command capability does not support annotations");
    }

    CommandRegistration register(
            Scope owner,
            String name,
            Consumer<? super CommandSpec> configure);
}
