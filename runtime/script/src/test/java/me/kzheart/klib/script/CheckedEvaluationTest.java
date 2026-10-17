package me.kzheart.klib.script;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import me.kzheart.klib.scope.ScopeImpl;
import me.kzheart.klib.script.kether.core.QuestAction;
import me.kzheart.klib.script.kether.core.QuestActionParser;
import me.kzheart.klib.script.kether.core.QuestContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CheckedEvaluationTest {
    @Test void compilationFailsSynchronouslyWithoutChangingEvalContract() {
        KetherScriptEngine engine = new KetherScriptEngine(new StatementRegistry());
        ScriptContext context = ScriptContext.builder().build();
        ScriptException checked = assertThrows(ScriptException.class,
                () -> engine.evalChecked("missing_action", context));
        CompletableFuture<Object> ordinary = assertDoesNotThrow(() ->
                engine.eval("missing_action", context).toCompletableFuture());
        CompletionException failure = assertThrows(CompletionException.class, ordinary::join);
        assertEquals(checked.getMessage(), failure.getCause().getMessage());
    }

    @Test void executionFailureEvenWhenImmediateIsReturnedInFuture() {
        StatementRegistry registry = new StatementRegistry();
        ScopeImpl scope = new ScopeImpl("checked-failure");
        try {
            registry.register(scope, "failnow", (call, context) -> { throw new IllegalStateException("runtime failure"); });
            KetherScriptEngine engine = new KetherScriptEngine(registry);
            CompletableFuture<Object> result = assertDoesNotThrow(() ->
                    engine.evalChecked("failnow", ScriptContext.builder().build()).toCompletableFuture());
            assertThrows(CompletionException.class, result::join);
        } finally { scope.close(); }
    }

    @Test void checkedAndOrdinaryShareCacheWithoutDoubleParsingOrRunning() {
        StatementRegistry registry = new StatementRegistry();
        AtomicInteger parsed = new AtomicInteger(), ran = new AtomicInteger();
        ScopeImpl scope = new ScopeImpl("checked-once");
        try {
            registry.registerKether(scope, "global", "once", QuestActionParser.of(reader -> {
                parsed.incrementAndGet();
                return new QuestAction<Object>() {
                    @Override public CompletableFuture<Object> process(QuestContext.Frame frame) {
                        return CompletableFuture.completedFuture(ran.incrementAndGet());
                    }
                };
            }));
            KetherScriptEngine engine = new KetherScriptEngine(registry);
            ScriptContext context = ScriptContext.builder().build();
            assertEquals(1, engine.evalChecked("once", context).toCompletableFuture().join());
            assertEquals(2, engine.eval("once", context).toCompletableFuture().join());
            assertEquals(1, parsed.get());
            assertEquals(1, engine.compilationCount());
        } finally { scope.close(); }
    }

    @Test void pendingExecutionRetainsFutureContract() {
        StatementRegistry registry = new StatementRegistry();
        CompletableFuture<Object> delayed = new CompletableFuture<Object>();
        ScopeImpl scope = new ScopeImpl("checked-pending");
        try {
            registry.register(scope, "pending", (call, context) -> delayed);
            KetherScriptEngine engine = new KetherScriptEngine(registry, null, Runnable::run);
            CompletableFuture<Object> result = engine.evalChecked("pending", ScriptContext.builder().build()).toCompletableFuture();
            assertFalse(result.isDone());
            delayed.complete("finished");
            assertEquals("finished", result.join());
        } finally { scope.close(); }
    }
}
