package me.kzheart.klib.script;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import me.kzheart.klib.scope.ScopeImpl;
import me.kzheart.klib.script.kether.core.ParsedAction;
import me.kzheart.klib.script.kether.core.QuestAction;
import me.kzheart.klib.script.kether.core.QuestActionParser;
import me.kzheart.klib.script.kether.core.QuestContext;
import me.kzheart.klib.script.kether.core.QuestFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(5)
class AwaitLifecycleTest {
    @Test
    void awaitPropagatesOuterActionFailureInsteadOfHanging() throws Exception {
        verifyFailure("await gate", false);
    }

    @Test
    void awaitPropagatesDeferredFailureInsteadOfHanging() throws Exception {
        verifyFailure("await async gate", false);
    }

    @Test
    void awaitPropagatesOuterCancellationInsteadOfHanging() throws Exception {
        verifyFailure("await gate", true);
    }

    @Test
    void awaitPropagatesDeferredCancellationInsteadOfHanging() throws Exception {
        verifyFailure("await async gate", true);
    }

    @Test
    void completedCancellationAlsoSettlesImmediately() {
        ScopeImpl scope = new ScopeImpl("completed-cancel");
        StatementRegistry registry = new StatementRegistry();
        CompletableFuture<Object> gate = new CompletableFuture<Object>();
        gate.cancel(false);
        register(registry, scope, gate);
        KetherScriptEngine engine = new KetherScriptEngine(registry, null, Runnable::run);
        CompletableFuture<Object> result = engine.eval("await gate", ScriptContext.builder().build()).toCompletableFuture();
        assertThrows(CancellationException.class, result::join);
        scope.close();
    }

    @Test
    void cancellationOfAwaitCancelsBorrowedDeferredFuture() {
        CompletableFuture<Object> deferred = new CompletableFuture<Object>();
        QuestFuture<Object> holder = new QuestFuture<Object>(ParsedAction.noop(), deferred);
        ScopeImpl scope = new ScopeImpl("holder");
        StatementRegistry registry = new StatementRegistry();
        register(registry, scope, CompletableFuture.completedFuture(holder));
        KetherScriptEngine engine = new KetherScriptEngine(registry, null, Runnable::run);
        CompletableFuture<Object> result = engine.eval("await gate", ScriptContext.builder().build()).toCompletableFuture();
        result.cancel(false);
        assertTrue(deferred.isCancelled());
        scope.close();
    }

    @Test
    void legacyQuestFutureCompletionHandlesFailureAndCancellation() {
        CompletableFuture<Object> deferred = new CompletableFuture<Object>();
        CompletableFuture<Object> result = new CompletableFuture<Object>();
        QuestFuture.complete(result).accept(new QuestFuture<Object>(ParsedAction.noop(), deferred));
        deferred.completeExceptionally(new IllegalArgumentException("broken"));
        assertThrows(CompletionException.class, result::join);
        CompletableFuture<Object> cancelled = new CompletableFuture<Object>();
        CompletableFuture<Object> output = new CompletableFuture<Object>();
        QuestFuture.complete(output).accept(new QuestFuture<Object>(ParsedAction.noop(), cancelled));
        cancelled.cancel(false);
        assertThrows(CancellationException.class, output::join);
    }

    private static void verifyFailure(String source, boolean cancellation) throws Exception {
        ScopeImpl scope = new ScopeImpl("await");
        StatementRegistry registry = new StatementRegistry();
        CompletableFuture<Object> gate = new CompletableFuture<Object>();
        register(registry, scope, gate);
        KetherScriptEngine engine = new KetherScriptEngine(registry, null, Runnable::run);
        CompletableFuture<Object> result = engine.eval(source, ScriptContext.builder().build()).toCompletableFuture();
        assertFalse(result.isDone());
        if (cancellation) gate.cancel(false);
        else gate.completeExceptionally(new IllegalArgumentException("broken"));
        if (cancellation) assertThrows(CancellationException.class, () -> result.get(1, TimeUnit.SECONDS));
        else {
            java.util.concurrent.ExecutionException failure = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> result.get(1, TimeUnit.SECONDS));
            assertInstanceOf(ScriptException.class, failure.getCause());
            assertTrue(failure.getCause().getMessage().contains("broken"));
        }
        scope.close();
    }

    static void register(StatementRegistry registry, ScopeImpl scope, CompletableFuture<Object> gate) {
        registry.registerKether(scope, "klib", "gate", QuestActionParser.of(reader -> new QuestAction<Object>() {
            @Override public CompletableFuture<Object> process(QuestContext.Frame frame) { return gate; }
        }));
    }
}
