package me.kzheart.klib.command;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Mounts an annotated handler under literal sub-routes of another command, e.g. every route of a
 * {@code /quest} handler also reachable as {@code /main quest ...}. The handler's own {@code @Command}
 * name and aliases are ignored for the mount; its permissions, checks and suggestions still apply.
 */
public final class MountedCommand {
    private final String command;
    private final List<String> literals;
    private final Object handler;

    private MountedCommand(String command, List<String> literals, Object handler) {
        this.command = command;
        this.literals = literals;
        this.handler = handler;
    }

    /**
     * {@code literal} and each alias become sibling prefixes in front of every handler route; a prefix may
     * span several words, e.g. {@code "quest data"} mounts under {@code /main quest data ...}.
     */
    public static MountedCommand of(String command, Object handler, String literal, String... aliases) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(handler, "handler");
        Objects.requireNonNull(literal, "literal");
        Objects.requireNonNull(aliases, "aliases");
        if (handler instanceof MountedCommand) throw new IllegalArgumentException("Nested mounts are not supported");
        List<String> literals = new ArrayList<String>();
        literals.add(literal);
        for (String alias : aliases) literals.add(Objects.requireNonNull(alias, "alias"));
        return new MountedCommand(command, Collections.unmodifiableList(literals), handler);
    }

    public String command() { return command; }
    public List<String> literals() { return literals; }
    public Object handler() { return handler; }
}
