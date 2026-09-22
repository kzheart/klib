package me.kzheart.klib.command;

import me.kzheart.klib.command.api.CommandArgument;
import me.kzheart.klib.command.api.CommandHandler;
import me.kzheart.klib.command.api.CommandSpec;

import java.util.Locale;
import java.util.function.Consumer;

public final class CommandSpecImpl implements CommandSpec {
    private final String name;
    private final CommandNode node;

    private CommandSpecImpl(String name, CommandNode node) {
        this.name = name;
        this.node = node;
    }

    public static CommandSpecImpl command(String name) {
        return new CommandSpecImpl(
                requireSingleWord(name, "command name"),
                new CommandNode(null, null));
    }

    /** 归一化为小写单词，供命令名 / literal / 参数名共用。 */
    static String requireSingleWord(String value, String what) {
        if (value == null) {
            throw new NullPointerException(what);
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || normalized.indexOf(' ') >= 0) {
            throw new IllegalArgumentException(what + " must be one non-blank word");
        }
        return normalized;
    }

    @Override
    public CommandSpec route(String path) {
        if (path == null || path.trim().isEmpty()) throw new IllegalArgumentException("route must not be blank");
        CommandSpecImpl current = this;
        for (String word : path.trim().split("\\s+")) {
            String literal = requireSingleWord(word, "literal");
            CommandSpecImpl next = null;
            for (CommandNode child : current.node.children) {
                if (literal.equals(child.literal)) next = new CommandSpecImpl(name, child);
            }
            if (next == null) {
                final CommandSpec[] created = new CommandSpec[1];
                current.literal(literal, child -> created[0] = child);
                next = (CommandSpecImpl) created[0];
            }
            current = next;
        }
        return current;
    }

    @Override
    public <T> CommandSpec argument(CommandArgument<T> argument) {
        final CommandSpec[] created = new CommandSpec[1];
        argument(argument, child -> created[0] = child);
        return created[0];
    }

    public String name() {
        return name;
    }

    @Override
    public CommandSpec description(String description) {
        node.description = description == null ? "" : description.trim();
        return this;
    }

    @Override
    public CommandSpec permission(String permission) {
        if (permission == null || permission.trim().isEmpty()) {
            throw new IllegalArgumentException("permission must not be blank");
        }
        node.permission = permission.trim();
        return this;
    }

    @Override
    public CommandSpec playerOnly() {
        node.playerOnly = true;
        return this;
    }

    @Override
    public CommandSpec executes(CommandHandler handler) {
        if (handler == null) {
            throw new NullPointerException("handler");
        }
        node.handler = handler;
        return this;
    }

    @Override
    public CommandSpec literal(String literal, Consumer<? super CommandSpec> configure) {
        requireCanAddChild(configure);
        String normalized = requireSingleWord(literal, "literal");
        for (CommandNode child : node.children) {
            if (normalized.equals(child.literal)) {
                throw new IllegalArgumentException("duplicate literal: " + normalized);
            }
        }
        CommandNode child = new CommandNode(normalized, null);
        node.children.add(child);
        configure.accept(new CommandSpecImpl(name, child));
        return this;
    }

    @Override
    public <T> CommandSpec argument(
            CommandArgument<T> argument,
            Consumer<? super CommandSpec> configure
    ) {
        requireCanAddChild(configure);
        if (!(argument instanceof Arg<?>)) {
            throw new IllegalArgumentException(
                    "命令参数必须由 Arguments 工厂创建；自定义解析请使用"
                            + " Arguments.custom(name, parser, suggester)");
        }
        Arg<?> typed = (Arg<?>) argument;
        for (CommandNode sibling : node.children) {
            if (sibling.argument == null) {
                continue;
            }
            if (sibling.argument.name().equals(typed.name())) {
                throw new IllegalArgumentException("duplicate argument: " + typed.name());
            }
            if (sibling.argument.isCatchAll()) {
                throw new IllegalStateException(
                        "argument <" + typed.name() + "> is unreachable after catch-all argument <"
                                + sibling.argument.name() + ">");
            }
        }
        CommandNode child = new CommandNode(null, typed);
        node.children.add(child);
        if (typed.isGreedy()) {
            node.hasGreedyChild = true;
        }
        configure.accept(new CommandSpecImpl(name, child));
        return this;
    }

    CommandSpecImpl withName(String label) { return new CommandSpecImpl(requireSingleWord(label, "command name"), node); }

    CommandNode root() {
        return node;
    }

    void descriptionKey(String key) {
        node.descriptionKey = key;
    }

    private void requireCanAddChild(Consumer<? super CommandSpec> configure) {
        if (configure == null) {
            throw new NullPointerException("configure");
        }
        if (node.argument != null && node.argument.isGreedy()) {
            throw new IllegalStateException("greedy argument must be the final node");
        }
        if (node.hasGreedyChild) {
            throw new IllegalStateException("no child may follow a greedy argument");
        }
    }
}
