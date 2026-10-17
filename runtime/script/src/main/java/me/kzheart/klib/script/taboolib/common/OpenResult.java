package me.kzheart.klib.script.taboolib.common;

/** 跨加载器协议 getter 保留 Java 平台返回类型。 */
public final class OpenResult {
    private static final OpenResult FAILED = new OpenResult(false, null);
    private final OpenResultValue state;
    private OpenResult(boolean successful, Object value) { state = new OpenResultValue(successful, value); }
    public static OpenResult successful(Object value) { return new OpenResult(true, value); }
    public static OpenResult failed() { return FAILED; }
    public boolean isSuccessful() { return state.getSuccessful(); }
    public Object getValue() { return state.getValue(); }
}
