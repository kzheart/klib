package me.kzheart.klib.command;
import me.kzheart.klib.command.api.CommandContext;
@FunctionalInterface
public interface ContextualArgumentParser<T> { T parse(String input, CommandContext context); }
