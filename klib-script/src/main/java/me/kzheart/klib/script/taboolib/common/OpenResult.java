package me.kzheart.klib.script.taboolib.common;

/** TabooLib OpenResult.cast 可跨类加载器读取的协议返回形状。 */
public final class OpenResult {
    private static final OpenResult FAILED = new OpenResult(false, null);
    private final boolean successful;
    private final Object value;

    private OpenResult(boolean successful, Object value) {
        this.successful = successful;
        this.value = value;
    }

    public static OpenResult successful(Object value) {
        return new OpenResult(true, value);
    }

    public static OpenResult failed() {
        return FAILED;
    }

    public boolean isSuccessful() {
        return successful;
    }

    public Object getValue() {
        return value;
    }
}
