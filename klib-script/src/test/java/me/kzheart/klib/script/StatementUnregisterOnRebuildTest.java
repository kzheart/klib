package me.kzheart.klib.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import me.kzheart.klib.scope.ScopeImpl;
import org.junit.jupiter.api.Test;

class StatementUnregisterOnRebuildTest {
    @Test
    void creatingAnotherEngineDoesNotCoverAUserOverride() {
        StatementRegistry registry = new StatementRegistry();
        new KetherScriptEngine(registry);
        ScopeImpl scope = new ScopeImpl("override");
        registry.register(scope, "klib", "literal", (call, context) ->
                java.util.concurrent.CompletableFuture.<Object>completedFuture("custom"));

        KetherScriptEngine second = new KetherScriptEngine(registry);

        Object value = second.eval("literal ignored", ScriptContext.builder().build())
                .toCompletableFuture().join();
        assertEquals("custom", value);
        scope.close();
    }


    @Test
    void rebuildRemovesOldParserBeforeRegisteringReplacement() {
        StatementRegistry registry = new StatementRegistry();
        KetherScriptEngine engine = new KetherScriptEngine(registry);
        AtomicInteger generation = new AtomicInteger();

        ScopeImpl scope = ScopeImpl.create("script", current -> {
            final int version = generation.incrementAndGet();
            registry.register(current, "demo", "probe", (call, context) ->
                    CompletableFuture.completedFuture("version-" + version));
        });

        assertEquals("version-1", eval(engine, "demo:probe"));
        assertEquals(1L, registry.registeredNames().stream()
                .filter("demo:probe"::equals)
                .count());

        scope.rebuild();

        assertEquals("version-2", eval(engine, "demo:probe"));
        assertEquals(1L, registry.registeredNames().stream()
                .filter("demo:probe"::equals)
                .count());
        assertTrue(registry.resolve("probe", java.util.Collections.singletonList("demo")).isPresent());

        scope.close();

        assertFalse(registry.resolve("probe", java.util.Collections.singletonList("demo")).isPresent());
    }

    private static Object eval(KetherScriptEngine engine, String source) {
        return engine.eval(source, ScriptContext.builder().build()).toCompletableFuture().join();
    }
}
