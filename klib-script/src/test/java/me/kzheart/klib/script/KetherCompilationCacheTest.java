package me.kzheart.klib.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import me.kzheart.klib.scope.ScopeImpl;
import org.junit.jupiter.api.Test;

class KetherCompilationCacheTest {

    @Test
    void repeatedEvaluationReusesCompiledQuestWithoutCapturingContextServices() {
        StatementRegistry registry = new StatementRegistry();
        ScopeImpl scope = new ScopeImpl("cache-services");
        registry.register(scope, "probe", (call, context) -> CompletableFuture.completedFuture(
                context.requireService(ProbeService.class).value));
        KetherScriptEngine engine = new KetherScriptEngine(registry);
        ScriptContext first = serviceContext("first");
        ScriptContext second = serviceContext("second");

        assertEquals("first", eval(engine, "probe", first));
        assertEquals("second", eval(engine, "probe", second));

        assertEquals(1L, engine.compilationCount());
        assertEquals(1, engine.cachedScriptCount());
        assertTrue(first.variables().isEmpty());
        assertTrue(second.variables().isEmpty());
        scope.close();
    }

    @Test
    void registryChangesInvalidateCachedCompilation() {
        StatementRegistry registry = new StatementRegistry();
        ScopeImpl first = new ScopeImpl("first");
        registry.register(first, "probe", (call, context) ->
                CompletableFuture.completedFuture("v1"));
        KetherScriptEngine engine = new KetherScriptEngine(registry);
        assertEquals("v1", eval(engine, "probe", ScriptContext.builder().build()));
        assertEquals("v1", eval(engine, "probe", ScriptContext.builder().build()));
        assertEquals(1L, engine.compilationCount());

        ScopeImpl second = new ScopeImpl("second");
        registry.register(second, "probe", (call, context) ->
                CompletableFuture.completedFuture("v2"));
        assertEquals("v2", eval(engine, "probe", ScriptContext.builder().build()));
        assertEquals(2L, engine.compilationCount());

        second.close();
        assertEquals("v1", eval(engine, "probe", ScriptContext.builder().build()));
        assertEquals(3L, engine.compilationCount());
        first.close();
    }

    @Test
    void namespaceOrderParticipatesInTheCacheKey() {
        StatementRegistry registry = new StatementRegistry();
        ScopeImpl scope = new ScopeImpl("namespaces");
        registry.register(scope, "alpha", "probe", (call, context) ->
                CompletableFuture.completedFuture("alpha"));
        registry.register(scope, "beta", "probe", (call, context) ->
                CompletableFuture.completedFuture("beta"));
        KetherScriptEngine engine = new KetherScriptEngine(registry);

        ScriptContext alphaFirst = ScriptContext.builder().namespaces("alpha", "beta").build();
        ScriptContext betaFirst = ScriptContext.builder().namespaces("beta", "alpha").build();
        assertEquals("alpha", eval(engine, "probe", alphaFirst));
        assertEquals("beta", eval(engine, "probe", betaFirst));
        assertEquals(2L, engine.compilationCount());
        scope.close();
    }

    @Test
    void cacheHasAHardEntryLimit() {
        KetherScriptEngine engine = new KetherScriptEngine(new StatementRegistry());

        for (int index = 0; index < 140; index++) {
            assertEquals("value-" + index, eval(
                    engine,
                    "literal value-" + index,
                    ScriptContext.builder().build()));
        }

        assertEquals(140L, engine.compilationCount());
        assertTrue(engine.cachedScriptCount() <= 128);
    }

    @Test
    void oversizedSourcesAreCompiledButNotRetained() {
        KetherScriptEngine engine = new KetherScriptEngine(new StatementRegistry());
        StringBuilder source = new StringBuilder("literal ");
        while (source.length() <= 65 * 1024) {
            source.append('x');
        }

        String script = source.toString();
        eval(engine, script, ScriptContext.builder().build());
        eval(engine, script, ScriptContext.builder().build());

        assertEquals(2L, engine.compilationCount());
        assertEquals(0, engine.cachedScriptCount());
    }

    private static ScriptContext serviceContext(String value) {
        return ScriptContext.builder().service(ProbeService.class, new ProbeService(value)).build();
    }

    private static Object eval(KetherScriptEngine engine, String source, ScriptContext context) {
        return engine.eval(source, context).toCompletableFuture().join();
    }

    private static final class ProbeService {
        private final String value;

        private ProbeService(String value) {
            this.value = value;
        }
    }
}
