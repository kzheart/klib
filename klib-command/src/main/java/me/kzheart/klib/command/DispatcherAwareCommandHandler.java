package me.kzheart.klib.command;

import me.kzheart.klib.command.api.CommandContext;
import me.kzheart.klib.command.api.CommandHandler;

interface DispatcherAwareCommandHandler extends CommandHandler {
    void execute(CommandContext context, CommandDispatcher dispatcher);

    @Override
    default void execute(CommandContext context) {
        throw new IllegalStateException("dispatcher-aware handler requires CommandDispatcher");
    }
}
