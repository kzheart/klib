package me.kzheart.klib.command;

import me.kzheart.klib.command.api.CommandHandler;

import java.util.ArrayList;
import java.util.List;

final class CommandNode {
    final String literal;
    final Arg<?> argument;
    final List<CommandNode> children = new ArrayList<CommandNode>();

    String description = "";
    String descriptionKey;
    String permission;
    boolean playerOnly;
    boolean hasGreedyChild;
    CommandHandler handler;

    CommandNode(String literal, Arg<?> argument) {
        this.literal = literal;
        this.argument = argument;
    }

    String usageToken() {
        return literal == null ? argument.usage() : literal;
    }
}
