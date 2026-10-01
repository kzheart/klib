package me.kzheart.klib.script;

/** 宿主显式提供的脚本对象属性适配；不根据成员名字反射猜测。 */
public interface ScriptPropertyAccess {
    Result read(Object instance, String key);
    Result write(Object instance, String key, Object value);

    /** 区分“支持且值为 null”和“不支持该属性”。 */
    final class Result {
        private final boolean supported;
        private final Object value;
        private Result(boolean supported, Object value) { this.supported = supported; this.value = value; }
        public static Result supported(Object value) { return new Result(true, value); }
        public static Result unsupported() { return new Result(false, null); }
        public boolean isSupported() { return supported; }
        public Object value() { return value; }
    }
}
