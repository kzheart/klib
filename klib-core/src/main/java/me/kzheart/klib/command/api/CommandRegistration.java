package me.kzheart.klib.command.api;

import me.kzheart.klib.scope.Disposable;

public interface CommandRegistration extends Disposable {
    String name();
}
