package me.kzheart.klib.script;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import me.kzheart.klib.scope.ScopeImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(5)
class ScopedScriptRuntimeTest {
    @Test
    void cancelledPublicEvaluationCancelsNativeDelayAndSuppressesActions() {
        ScopeImpl scope = new ScopeImpl("cancel");
        Harness host = new Harness();
        KetherScriptEngine engine = new KetherScriptEngine(new StatementRegistry(), null, host, true);
        CompletableFuture<Object> result = engine.eval("wait 1s\ntell stale", host.context()).toCompletableFuture();
        assertTrue(result.cancel(false));
        assertTrue(host.delay.isCancelled());
        host.drain();
        assertTrue(host.messages.isEmpty());
        scope.close();
    }

    @Test
    void closeCancelsRootWithoutUsingClosedContinuationScheduler() {
        ScopeImpl scope = new ScopeImpl("close");
        Harness host = new Harness();
        ScopedScriptRuntime runtime = new ScopedScriptRuntime(scope, new StatementRegistry(), null, host, true);
        CompletableFuture<Object> result = runtime.eval("wait 1s\ntell stale", host.context()).toCompletableFuture();
        host.reject = true;
        scope.close();
        assertTrue(result.isCancelled());
        assertTrue(host.delay.isCancelled());
        assertEquals(0, runtime.activeExecutionCount());
        assertThrows(IllegalStateException.class, () -> runtime.eval("*new", host.context()));
    }

    @Test
    void reloadDropsAlreadyQueuedContinuationAndAcceptsNewExecution() {
        ScopeImpl scope = new ScopeImpl("reload");
        Harness host = new Harness();
        ScopedScriptRuntime runtime = new ScopedScriptRuntime(scope, new StatementRegistry(), null, host, true);
        CompletableFuture<Object> result = runtime.eval("wait 1s\ntell stale", host.context()).toCompletableFuture();
        host.delay.complete(null);
        assertFalse(host.tasks.isEmpty());
        runtime.cancelPending();
        host.drain();
        assertTrue(result.isCancelled());
        assertTrue(host.messages.isEmpty());
        assertEquals("fresh", runtime.eval("tell fresh", host.context()).toCompletableFuture().join());
        assertEquals(1, host.messages.size());
        scope.close();
    }

    @Test
    void detachedAsyncRemainsTrackedAfterRootReturnsAndCanFinish() {
        ScopeImpl scope = new ScopeImpl("detached");
        Harness host = new Harness();
        ScopedScriptRuntime runtime = new ScopedScriptRuntime(scope, new StatementRegistry(), null, host, true);
        CompletableFuture<Object> result = runtime.eval("async { wait 1s\ntell later }\n*done", host.context()).toCompletableFuture();
        assertEquals("done", result.join());
        assertEquals(1, runtime.activeExecutionCount());
        host.delay.complete(null);
        host.drain();
        assertEquals("later", host.messages.get(0));
        assertEquals(0, runtime.activeExecutionCount());
        scope.close();
    }

    @Test
    void detachedAsyncIsCancelledOnScopeCloseEvenWithCompletedRoot() {
        ScopeImpl scope = new ScopeImpl("detached-close");
        Harness host = new Harness();
        ScopedScriptRuntime runtime = new ScopedScriptRuntime(scope, new StatementRegistry(), null, host, true);
        assertEquals("done", runtime.eval("async { wait 1s\ntell stale }\n*done", host.context()).toCompletableFuture().join());
        scope.close();
        assertTrue(host.delay.isCancelled());
        host.drain();
        assertTrue(host.messages.isEmpty());
        assertEquals(0, runtime.activeExecutionCount());
    }

    @Test
    void offThreadCompletionReturnsToInjectedExecutor() throws Exception {
        ScopeImpl scope = new ScopeImpl("thread");
        Harness host = new Harness();
        ScopedScriptRuntime runtime = new ScopedScriptRuntime(scope, new StatementRegistry(), null, host, true);
        AtomicReference<Thread> observed = new AtomicReference<Thread>();
        ScriptContext context = ScriptContext.builder().service(DelayScheduler.class, duration -> host.delay)
                .service(MessageSink.class, (sender, message) -> observed.set(Thread.currentThread())).build();
        CompletableFuture<Object> result = runtime.eval("await async { wait 1s }\ntell resumed", context).toCompletableFuture();
        Thread worker = new Thread(() -> host.delay.complete(null), "completion-worker");
        worker.start();
        worker.join();
        assertNull(observed.get());
        host.drain();
        assertEquals("resumed", result.get(1, TimeUnit.SECONDS));
        assertSame(Thread.currentThread(), observed.get());
        scope.close();
    }

    @Test
    void engineAccessorCannotExecuteAfterClose() {
        ScopeImpl scope = new ScopeImpl("accessor");
        Harness host = new Harness();
        ScopedScriptRuntime runtime = new ScopedScriptRuntime(scope, new StatementRegistry(), null, host, true);
        runtime.close();
        CompletableFuture<Object> result = runtime.engine().eval("tell forbidden", host.context()).toCompletableFuture();
        assertThrows(CancellationException.class, result::join);
        assertTrue(host.messages.isEmpty());
        scope.close();
    }

    @Test
    void failedRootCancelsDetachedWorkAndReleasesExecution() {
        ScopeImpl scope = new ScopeImpl("failed-root");
        StatementRegistry registry = new StatementRegistry();
        registry.register(scope, "fail", (call, context) -> { throw new IllegalStateException("failed root"); });
        Harness host = new Harness();
        ScopedScriptRuntime runtime = new ScopedScriptRuntime(scope, registry, null, host, true);
        CompletableFuture<Object> result = runtime.eval("async { wait 1s\ntell stale }\nfail", host.context()).toCompletableFuture();
        assertThrows(java.util.concurrent.CompletionException.class, result::join);
        assertTrue(host.delay.isCancelled());
        host.drain();
        assertTrue(host.messages.isEmpty());
        assertEquals(0, runtime.activeExecutionCount());
        scope.close();
    }

    @Test
    void cancellationOfConditionAndLegacyDelayCancelsUnderlyingFuture() {
        ScopeImpl scope = new ScopeImpl("condition-cancel");
        Harness host = new Harness();
        ScopedScriptRuntime runtime = new ScopedScriptRuntime(scope, new StatementRegistry(), null, host, true);
        CompletableFuture<Boolean> result = runtime.evalCondition("delay 1ms\ntrue", host.context()).toCompletableFuture();
        scope.close();
        assertTrue(result.isCancelled());
        assertTrue(host.delay.isCancelled());
        assertEquals(0, runtime.activeExecutionCount());
    }

    @Test
    void cancellingDerivedConditionFuturePropagatesBackToNativeExecution() {
        ScopeImpl scope = new ScopeImpl("condition-caller-cancel");
        Harness host = new Harness();
        ScopedScriptRuntime runtime = new ScopedScriptRuntime(scope, new StatementRegistry(), null, host, true);
        CompletableFuture<Boolean> result = runtime.evalCondition("wait 1s\ntell forbidden\ntrue", host.context()).toCompletableFuture();
        assertTrue(result.cancel(false));
        assertTrue(host.delay.isCancelled());
        host.drain();
        assertTrue(host.messages.isEmpty());
        assertEquals(0, runtime.activeExecutionCount());
        scope.close();
    }

    @Test
    void scopeRebuildClosesOldRuntimeAndSuccessfulExecutionsDoNotAccumulate() {
        ScopeImpl scope = new ScopeImpl("rebuild");
        Harness host = new Harness();
        ScopedScriptRuntime runtime = new ScopedScriptRuntime(scope, new StatementRegistry(), null, host, true);
        for (int index = 0; index < 100; index++) runtime.eval("*done", host.context()).toCompletableFuture().join();
        assertEquals(0, runtime.activeExecutionCount());
        CompletableFuture<Object> old = runtime.eval("wait 1s\ntell stale", host.context()).toCompletableFuture();
        scope.rebuild();
        assertTrue(old.isCancelled());
        assertThrows(IllegalStateException.class, () -> runtime.eval("*old", host.context()));
        ScopedScriptRuntime next = new ScopedScriptRuntime(scope, new StatementRegistry(), null, host, true);
        assertEquals("fresh", next.eval("*fresh", host.context()).toCompletableFuture().join());
        scope.close();
    }

    @Test
    void explicitExitCancelsDetachedActionsAndReleasesExecution() {
        ScopeImpl scope = new ScopeImpl("detached-exit");
        Harness host = new Harness();
        ScopedScriptRuntime runtime = new ScopedScriptRuntime(scope, new StatementRegistry(), null, host, true);
        assertNull(runtime.eval("async { wait 1s\ntell stale }\nexit", host.context()).toCompletableFuture().join());
        assertTrue(host.delay.isCancelled());
        host.drain();
        assertTrue(host.messages.isEmpty());
        assertEquals(0, runtime.activeExecutionCount());
        scope.close();
    }

    static final class Harness implements Executor {
        final Deque<Runnable> tasks = new ArrayDeque<Runnable>();
        final List<String> messages = new ArrayList<String>();
        final CompletableFuture<Object> delay = new CompletableFuture<Object>();
        boolean reject;
        @Override public synchronized void execute(Runnable task) {
            if (reject) throw new java.util.concurrent.RejectedExecutionException("closed scheduler");
            tasks.add(task);
        }
        void drain() {
            Runnable task;
            while ((task = tasks.poll()) != null) task.run();
        }
        ScriptContext context() {
            return ScriptContext.builder().service(DelayScheduler.class, duration -> delay)
                    .service(MessageSink.class, (sender, message) -> messages.add(message)).build();
        }
    }
}
