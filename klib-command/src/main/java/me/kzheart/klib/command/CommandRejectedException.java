package me.kzheart.klib.command;
/** An expected business refusal, safe to display to the sender. */
public final class CommandRejectedException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public CommandRejectedException(String message) { super(java.util.Objects.requireNonNull(message, "message")); }
}
