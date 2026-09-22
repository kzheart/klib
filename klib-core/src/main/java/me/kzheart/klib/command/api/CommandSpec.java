package me.kzheart.klib.command.api;

import java.util.function.Consumer;

public interface CommandSpec {
    /** Descend through a space-separated literal path; existing literal nodes are reused. */
    default CommandSpec route(String path) {
        throw new UnsupportedOperationException("Flat routes require klib-command");
    }

    /** Append one argument and return its child node for flat chaining. */
    default <T> CommandSpec argument(CommandArgument<T> argument) {
        throw new UnsupportedOperationException("Flat arguments require klib-command");
    }

    CommandSpec description(String description);

    CommandSpec permission(String permission);

    CommandSpec playerOnly();

    CommandSpec executes(CommandHandler handler);

    CommandSpec literal(String literal, Consumer<? super CommandSpec> configure);

    <T> CommandSpec argument(
            CommandArgument<T> argument,
            Consumer<? super CommandSpec> configure);
}
