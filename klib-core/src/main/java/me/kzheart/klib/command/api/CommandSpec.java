package me.kzheart.klib.command.api;

import java.util.function.Consumer;

public interface CommandSpec {
    CommandSpec description(String description);

    CommandSpec permission(String permission);

    CommandSpec playerOnly();

    CommandSpec executes(CommandHandler handler);

    CommandSpec literal(String literal, Consumer<? super CommandSpec> configure);

    <T> CommandSpec argument(
            CommandArgument<T> argument,
            Consumer<? super CommandSpec> configure);
}
