package me.kzheart.klib.script;

import java.io.IOException;
import java.io.Reader;
import java.net.URL;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import javax.script.AbstractScriptEngine;
import javax.script.Bindings;
import javax.script.ScriptEngineFactory;
import javax.script.ScriptException;
import javax.script.SimpleBindings;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ScriptJavaScriptEnginesTest {
    @Test void namedProviderDoesNotDiscoverEnginesUntilFactoryInvocation() {
        AtomicInteger lookups = new AtomicInteger();
        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
            @Override public Enumeration<URL> getResources(String name) throws IOException {
                if ("META-INF/services/javax.script.ScriptEngineFactory".equals(name)) lookups.incrementAndGet();
                return Collections.enumeration(Collections.<URL>emptyList());
            }
        };
        Supplier<JavaScriptEvaluator> factory = ScriptJavaScriptEngines.named("missing", loader);
        assertEquals(0, lookups.get());
        IllegalStateException failure = assertThrows(IllegalStateException.class, factory::get);
        assertTrue(lookups.get() > 0);
        assertTrue(failure.getMessage().contains("missing"));
        assertTrue(failure.getMessage().contains("not available"));
    }

    @Test void adapterCopiesBindingsAndCreatesNewBindingsForEveryEvaluation() {
        FakeEngine engine = new FakeEngine();
        JavaScriptEvaluator evaluator = ScriptJavaScriptEngines.using(engine);
        Map<String, Object> bindings = Collections.<String, Object>singletonMap("value", "original");
        assertEquals("original", evaluator.eval("read", bindings));
        assertEquals("original", evaluator.eval("write", bindings));
        assertEquals("original", bindings.get("value"));
        assertEquals("original", evaluator.eval("read", bindings));
        assertNull(evaluator.eval("read", Collections.<String, Object>emptyMap()));
    }

    @Test void adapterReportsScriptFailuresAndKeepsTheEngineUsable() {
        JavaScriptEvaluator evaluator = ScriptJavaScriptEngines.using(new FakeEngine());
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> evaluator.eval("fail", Collections.<String, Object>emptyMap()));
        assertTrue(failure.getMessage().contains("expected script failure"));
        assertTrue(failure.getCause() instanceof ScriptException);
        assertEquals(7, evaluator.eval("read", Collections.<String, Object>singletonMap("value", 7)));
    }

    @Test void invalidFactoryArgumentsFailBeforeExecution() {
        assertThrows(IllegalArgumentException.class,
                () -> ScriptJavaScriptEngines.named(" ", getClass().getClassLoader()));
        assertThrows(NullPointerException.class, () -> ScriptJavaScriptEngines.using(null));
    }

    @Test void closingTheAdapterClosesItsEngineOnceAndRejectsLaterEvaluation() throws Exception {
        CloseableEngine engine = new CloseableEngine();
        JavaScriptEvaluator evaluator = ScriptJavaScriptEngines.using(engine);
        assertTrue(evaluator instanceof AutoCloseable);
        ((AutoCloseable) evaluator).close();
        ((AutoCloseable) evaluator).close();
        assertEquals(1, engine.closes);
        assertThrows(IllegalStateException.class,
                () -> evaluator.eval("read", Collections.<String, Object>emptyMap()));
    }

    private static final class CloseableEngine extends FakeEngine implements AutoCloseable {
        private int closes;
        @Override public void close() { closes++; }
    }

    private static class FakeEngine extends AbstractScriptEngine {
        @Override public Object eval(String source, javax.script.ScriptContext context) throws ScriptException {
            if ("fail".equals(source)) throw new ScriptException("expected script failure");
            Object value = context.getAttribute("value");
            if ("write".equals(source)) context.setAttribute("value", "changed", javax.script.ScriptContext.ENGINE_SCOPE);
            return value;
        }
        @Override public Object eval(Reader source, javax.script.ScriptContext context) { throw new UnsupportedOperationException(); }
        @Override public Bindings createBindings() { return new SimpleBindings(); }
        @Override public ScriptEngineFactory getFactory() { return null; }
    }
}
