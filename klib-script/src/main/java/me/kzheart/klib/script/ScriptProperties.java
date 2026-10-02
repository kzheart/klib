/* Kether property semantics: Copyright (c) 2018 Bkm016, MIT. See THIRD_PARTY_NOTICES.md. */
package me.kzheart.klib.script;

import java.lang.reflect.Array;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.regex.Matcher;
import me.kzheart.klib.script.kether.core.QuestContext;

/**
 * 属性读写：先交给宿主 {@link ScriptPropertyAccess}，未支持时使用原框架内置的
 * String、Map、List、数组与正则 Matcher 属性。
 */
final class ScriptProperties {
    private ScriptProperties() { }

    static Object read(QuestContext.Frame frame, Object instance, String key) {
        if (instance == null) {
            warn(frame, "Property object must be not null.");
            return null;
        }
        ScriptContext context = CoreScriptRuntime.context(frame);
        ScriptPropertyAccess host = context.service(ScriptPropertyAccess.class).orElse(null);
        if (host != null) {
            ScriptPropertyAccess.Result result = host.read(instance, key);
            if (result != null && result.isSupported()) return result.value();
        }
        ScriptPropertyAccess.Result builtin = readBuiltin(instance, key);
        if (builtin.isSupported()) return builtin.value();
        warn(frame, instance.getClass().getSimpleName() + "[" + key + "] not supported yet.");
        return null;
    }

    /** 返回是否写入成功；失败时由调用方决定报错方式。 */
    static boolean write(QuestContext.Frame frame, Object instance, String key, Object value) {
        ScriptPropertyAccess host = CoreScriptRuntime.context(frame).service(ScriptPropertyAccess.class).orElse(null);
        if (host != null) {
            ScriptPropertyAccess.Result result = host.write(instance, key, value);
            if (result != null && result.isSupported()) return true;
        }
        return writeBuiltin(instance, key, value);
    }

    static ScriptPropertyAccess.Result readBuiltin(Object instance, String key) {
        if (instance instanceof String) {
            String text = (String) instance;
            switch (key) {
                case "upper": case "uppercase": return supported(text.toUpperCase(Locale.ROOT));
                case "lower": case "lowercase": return supported(text.toLowerCase(Locale.ROOT));
                case "length": case "size": return supported(Integer.valueOf(text.length()));
                case "trim": return supported(text.trim());
                default: return ScriptPropertyAccess.Result.unsupported();
            }
        }
        if (instance instanceof Map<?, ?>) {
            Map<?, ?> map = (Map<?, ?>) instance;
            if (key.startsWith("@")) return supported(map.get(key.substring(1)));
            switch (key) {
                case "length": case "size": return supported(Integer.valueOf(map.size()));
                case "keys": return supported(map.keySet());
                case "values": return supported(map.values());
                default: return ScriptPropertyAccess.Result.unsupported();
            }
        }
        if (instance instanceof List<?>) {
            List<?> list = (List<?>) instance;
            if (KetherSupport.isInt(key)) return supported(list.get(Integer.parseInt(key)));
            if ("length".equals(key) || "size".equals(key)) return supported(Integer.valueOf(list.size()));
            return ScriptPropertyAccess.Result.unsupported();
        }
        if (instance.getClass().isArray()) {
            if (KetherSupport.isInt(key)) return supported(Array.get(instance, Integer.parseInt(key)));
            if ("length".equals(key) || "size".equals(key)) return supported(Integer.valueOf(Array.getLength(instance)));
            return ScriptPropertyAccess.Result.unsupported();
        }
        if (instance instanceof Matcher) {
            Matcher matcher = (Matcher) instance;
            try {
                return supported(KetherSupport.isInt(key) ? matcher.group(Integer.parseInt(key)) : matcher.group(key));
            } catch (RuntimeException ignored) {
                return ScriptPropertyAccess.Result.unsupported();
            }
        }
        return ScriptPropertyAccess.Result.unsupported();
    }

    @SuppressWarnings("unchecked")
    private static boolean writeBuiltin(Object instance, String key, Object value) {
        if (instance instanceof Map<?, ?> && key.startsWith("@")) {
            Map<Object, Object> map = (Map<Object, Object>) instance;
            if (value != null) map.put(key.substring(1), value);
            else map.remove(key.substring(1));
            return true;
        }
        if (instance instanceof List<?> && KetherSupport.isInt(key)) {
            ((List<Object>) instance).set(Integer.parseInt(key), value);
            return true;
        }
        if (instance != null && instance.getClass().isArray() && KetherSupport.isInt(key)) {
            Array.set(instance, Integer.parseInt(key), value);
            return true;
        }
        return false;
    }

    private static ScriptPropertyAccess.Result supported(Object value) { return ScriptPropertyAccess.Result.supported(value); }

    private static void warn(QuestContext.Frame frame, String message) {
        ScriptLogger logger = CoreScriptRuntime.context(frame).service(ScriptLogger.class).orElse(null);
        if (logger != null) logger.log(Level.WARNING, message);
    }
}
