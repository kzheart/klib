package me.kzheart.klib.command;

import me.kzheart.klib.lang.RichText;

public final class CommandResult {
    public enum Status {
        SUCCESS,
        HELP,
        NO_PERMISSION,
        PLAYER_ONLY,
        INVALID_ARGUMENT,
        UNKNOWN_ARGUMENT,
        INCOMPLETE,
        FAILED
    }

    private final Status status;
    private final RichText message;

    private CommandResult(Status status, RichText message) {
        this.status = status;
        this.message = message;
    }

    public static CommandResult success() {
        return new CommandResult(Status.SUCCESS, null);
    }

    public static CommandResult message(Status status, RichText message) {
        return new CommandResult(status, message);
    }

    public Status status() {
        return status;
    }

    public RichText message() {
        return message;
    }
}
