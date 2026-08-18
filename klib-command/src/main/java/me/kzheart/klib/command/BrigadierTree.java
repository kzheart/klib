package me.kzheart.klib.command;

import me.kzheart.klib.command.api.CommandSpec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class BrigadierTree {
    enum Kind {
        ROOT,
        LITERAL,
        ARGUMENT
    }

    private final Kind kind;
    private final String token;
    private final String permission;
    private final boolean playerOnly;
    private final boolean greedy;
    private final boolean handler;
    private final List<BrigadierTree> children;

    private BrigadierTree(
            Kind kind,
            String token,
            String permission,
            boolean playerOnly,
            boolean greedy,
            boolean handler,
            List<BrigadierTree> children
    ) {
        this.kind = kind;
        this.token = token;
        this.permission = permission;
        this.playerOnly = playerOnly;
        this.greedy = greedy;
        this.handler = handler;
        this.children = Collections.unmodifiableList(children);
    }

    static BrigadierTree from(CommandSpec spec) {
        if (!(spec instanceof CommandSpecImpl)) {
            throw new IllegalArgumentException("command spec must be created by klib");
        }
        CommandSpecImpl typed = (CommandSpecImpl) spec;
        return copy(Kind.ROOT, typed.name(), typed.root());
    }

    private static BrigadierTree copy(Kind rootKind, String rootToken, CommandNode node) {
        Kind nodeKind = rootKind;
        String nodeToken = rootToken;
        boolean greedy = false;
        if (node.literal != null) {
            nodeKind = Kind.LITERAL;
            nodeToken = node.literal;
        } else if (node.argument != null) {
            nodeKind = Kind.ARGUMENT;
            nodeToken = node.argument.name();
            greedy = node.argument.isGreedy();
        }
        List<BrigadierTree> children = new ArrayList<BrigadierTree>();
        for (CommandNode child : node.children) {
            children.add(copy(Kind.ROOT, "", child));
        }
        return new BrigadierTree(
                nodeKind,
                nodeToken,
                node.permission,
                node.playerOnly,
                greedy,
                node.handler != null,
                children);
    }

    Kind kind() {
        return kind;
    }

    String token() {
        return token;
    }

    String permission() {
        return permission;
    }

    boolean playerOnly() {
        return playerOnly;
    }

    boolean greedy() {
        return greedy;
    }

    boolean handler() {
        return handler;
    }

    List<BrigadierTree> children() {
        return children;
    }
}
