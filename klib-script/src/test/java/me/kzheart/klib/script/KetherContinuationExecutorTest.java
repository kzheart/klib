package me.kzheart.klib.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class KetherContinuationExecutorTest {

    @Test
    @Timeout(5)
    void convenienceConstructorFailsClosedForAsynchronousContinuations() throws Exception {
        CompletableFuture<Object> delayed = new CompletableFuture<Object>();
        AtomicReference<String> actionThread = new AtomicReference<String>();
        ScriptContext context = ScriptContext.builder()
                .service(DelayScheduler.class, duration -> delayed)
                .service(MessageSink.class, (sender, message) ->
                        actionThread.set(Thread.currentThread().getName()))
                .build();
        KetherScriptEngine engine = new KetherScriptEngine(new StatementRegistry());
        CompletableFuture<Object> result = engine.eval(
                "delay 1ms\ntell should-not-run", context).toCompletableFuture();

        Thread worker = new Thread(() -> delayed.complete(null), "database-worker");
        worker.start();
        worker.join();

        ExecutionException failure = assertThrows(
                ExecutionException.class,
                () -> result.get(2, TimeUnit.SECONDS));
        ScriptException scriptFailure = assertInstanceOf(
                ScriptException.class, failure.getCause());
        assertEquals("continuation-executor-required", scriptFailure.code());
        assertTrue(scriptFailure.getMessage().contains("Executor"));
        assertNull(actionThread.get());
    }

    @Test
    @Timeout(5)
    void asynchronousActionContinuationReturnsToInjectedExecutor() throws Exception {
        ExecutorService mainThread = Executors.newSingleThreadExecutor(runnable ->
                new Thread(runnable, "script-main"));
        try {
            CompletableFuture<Object> delayed = new CompletableFuture<Object>();
            AtomicReference<String> actionThread = new AtomicReference<String>();
            AtomicReference<String> completionThread = new AtomicReference<String>();
            ScriptContext context = ScriptContext.builder()
                    .service(DelayScheduler.class, duration -> delayed)
                    .service(MessageSink.class, (sender, message) ->
                            actionThread.set(Thread.currentThread().getName()))
                    .build();
            KetherScriptEngine engine = new KetherScriptEngine(
                    new StatementRegistry(), null, mainThread);
            CompletableFuture<Object> result = engine.eval(
                    "delay 1ms\ntell resumed", context).toCompletableFuture();
            CompletableFuture<Object> observed = result.whenComplete((value, failure) ->
                    completionThread.set(Thread.currentThread().getName()));

            Thread worker = new Thread(() -> delayed.complete(null), "database-worker");
            worker.start();
            worker.join();

            assertEquals("resumed", observed.get(2, TimeUnit.SECONDS));
            assertEquals("script-main", actionThread.get());
            assertEquals("script-main", completionThread.get());
        } finally {
            mainThread.shutdownNow();
        }
    }
}
