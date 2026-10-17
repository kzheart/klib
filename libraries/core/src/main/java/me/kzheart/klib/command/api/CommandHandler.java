package me.kzheart.klib.command.api;

@FunctionalInterface
public interface CommandHandler {
    void execute(CommandContext context);
}
