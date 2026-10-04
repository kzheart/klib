package me.kzheart.klib.command;

/** Explicit ownership policy for collisions of unqualified Bukkit command labels. */
public enum CommandRegistrationPolicy {
    /** Retains existing bindings and rejects a conflicting root registration. */
    REJECT,
    /** Replaces only bare labels at actual registration; restores still-live prior bindings on close. */
    REPLACE_UNQUALIFIED
}
