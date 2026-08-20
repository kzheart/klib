package me.kzheart.klib.guard.kether;

/** 跨类加载器调用的父加载器返回形状。 */
public final class KetherInteropResult {

    private static final KetherInteropResult FAILED = new KetherInteropResult(false, null);

    private final boolean successful;
    private final Object value;

    private KetherInteropResult(boolean successful, Object value) {
        this.successful = successful;
        this.value = value;
    }

    public static KetherInteropResult successful() {
        return successful(null);
    }

    public static KetherInteropResult successful(Object value) {
        return new KetherInteropResult(true, value);
    }

    public static KetherInteropResult failed() {
        return FAILED;
    }

    public boolean isSuccessful() {
        return successful;
    }

    public Object getValue() {
        return value;
    }
}
