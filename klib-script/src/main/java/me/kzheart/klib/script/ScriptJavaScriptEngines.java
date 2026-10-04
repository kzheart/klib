package me.kzheart.klib.script;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import javax.script.SimpleBindings;

/** Java 8 JSR-223 适配器；不对某个 JavaScript 引擎或它的最低 Java 版本建立依赖。 */
public final class ScriptJavaScriptEngines {
    private ScriptJavaScriptEngines() { }

    /**
     * 返回一个尚未发现引擎的工厂；每次调用工厂创建独立引擎。
     * 交给 {@link BukkitScriptHost.Builder#javascript(Supplier)} 后只在首次求值创建一次。
     */
    public static Supplier<JavaScriptEvaluator> named(String engineName, ClassLoader loader) {
        String name = Objects.requireNonNull(engineName, "engineName").trim();
        if (name.isEmpty()) throw new IllegalArgumentException("JavaScript engine name must not be blank");
        Objects.requireNonNull(loader, "loader");
        return () -> {
            ScriptEngine engine;
            try {
                engine = new ScriptEngineManager(loader).getEngineByName(name);
            } catch (RuntimeException | LinkageError failure) {
                throw new IllegalStateException("Failed to initialize JavaScript engine '" + name
                        + "'. Check its runtime dependencies.", failure);
            }
            if (engine == null) {
                throw new IllegalStateException("JavaScript engine '" + name + "' is not available. "
                        + "Provide a compatible JSR-223 engine or configure an explicit JavaScript evaluator factory.");
            }
            return using(engine);
        };
    }

    /**
     * 包装插件显式创建的 JSR-223 引擎，每次执行使用隔离的变量映射。
     * 该适配器不会复制绑定对象本身；脚本仍可调用宿主对象，不能作为不可信脚本沙箱。
     * 返回的求值器实现 AutoCloseable，关闭时会释放实现 AutoCloseable 的底层引擎。
     */
    public static JavaScriptEvaluator using(ScriptEngine engine) {
        return new EngineEvaluator(Objects.requireNonNull(engine, "engine"));
    }

    private static final class EngineEvaluator implements JavaScriptEvaluator, AutoCloseable {
        private ScriptEngine engine;

        private EngineEvaluator(ScriptEngine engine) { this.engine = engine; }

        @Override public Object eval(String source, Map<String, Object> bindings) {
            if (engine == null) throw new IllegalStateException("JavaScript evaluator is closed");
            try {
                return engine.eval(Objects.requireNonNull(source, "source"),
                        new SimpleBindings(new LinkedHashMap<String, Object>(
                                Objects.requireNonNull(bindings, "bindings"))));
            } catch (ScriptException failure) {
                throw new IllegalArgumentException("JavaScript evaluation failed: " + failure.getMessage(), failure);
            }
        }

        @Override public void close() {
            ScriptEngine closing = engine;
            engine = null;
            if (closing instanceof AutoCloseable) {
                try {
                    ((AutoCloseable) closing).close();
                } catch (Exception failure) {
                    if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                    throw new IllegalStateException("Failed to close the JavaScript engine", failure);
                }
            }
        }
    }
}
