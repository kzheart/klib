package me.kzheart.klib.command;

import me.kzheart.klib.command.api.CommandRegistration;
import me.kzheart.klib.scope.Disposable;

final class CommandRegistrationImpl implements CommandRegistration {
    private final String name;
    private final Disposable delegate;
    private boolean disposed;

    CommandRegistrationImpl(String name, Disposable delegate) {
        if (delegate == null) {
            throw new NullPointerException("delegate");
        }
        this.name = name;
        this.delegate = delegate;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public synchronized void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        delegate.dispose();
    }
}
