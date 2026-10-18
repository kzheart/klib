package me.kzheart.klib.script.taboolib.common;

/** Cross-loader protocol: legacy containers read these exact fields, not only the getters. */
public final class OpenResult {
    private static final OpenResult FAILED = new OpenResult(false, null);
    private final boolean successful;
    private final Object value;
    private OpenResult(boolean successful, Object value) { this.successful = successful; this.value = value; }
    public static OpenResult successful(Object value) { return new OpenResult(true, value); }
    public static OpenResult failed() { return FAILED; }
    public boolean isSuccessful() { return successful; }
    public Object getValue() { return value; }
}
