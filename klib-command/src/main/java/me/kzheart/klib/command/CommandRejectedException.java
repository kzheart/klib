package me.kzheart.klib.command;

import java.util.Objects;

/** An expected business refusal, safe to display to the sender. */
public final class CommandRejectedException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public CommandRejectedException(String message) { super(Objects.requireNonNull(message, "message")); }
}
