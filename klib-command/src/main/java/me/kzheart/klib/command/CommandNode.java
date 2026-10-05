package me.kzheart.klib.command;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

import me.kzheart.klib.command.api.CommandHandler;
import me.kzheart.klib.command.api.CommandErrorHandler;
import me.kzheart.klib.command.api.CommandHelpStyle;
import org.bukkit.command.CommandSender;

final class CommandNode {
    final String literal;
    final Arg<?> argument;
    final List<CommandNode> children = new ArrayList<CommandNode>();

    String description = "";
    String descriptionKey;
    String permission;
    boolean playerOnly;
    boolean handlerPlayerOnly;
    Object suggestionOwner;
    boolean hasGreedyChild;
    CommandHandler handler;
    CommandErrorHandler errorHandler;
    CommandNode parent;
    Supplier<CommandHelpStyle> helpStyle;
    Predicate<CommandSender> branchAccess;
    Predicate<CommandSender> handlerAccess;


    CommandNode(String literal, Arg<?> argument) {
        this.literal = literal;
        this.argument = argument;
    }

    String usageToken() {
        return literal == null ? argument.usage() : literal;
    }
}
