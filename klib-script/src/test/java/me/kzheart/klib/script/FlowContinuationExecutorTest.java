package me.kzheart.klib.script;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import me.kzheart.klib.scope.ScopeImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(5)
class FlowContinuationExecutorTest {
    @Test
    void seqReturnsToExecutorBeforeNextAction() throws Exception {
        verify("seq [ gate { tell resumed } ]", "ready");
    }

    @Test
    void forReturnsToExecutorAfterAsynchronousSource() throws Exception {
        verify("for entry in gate then { tell resumed }", Arrays.asList("one"));
    }

    @Test
    void whileReturnsToExecutorAfterAsynchronousCondition() throws Exception {
        verify("while gate then { tell resumed\nbreak }", Boolean.TRUE);
    }

    @Test
    void joinReturnsToExecutorBeforeEvaluatingTheNextElement() throws Exception {
        verify("join [ gate { tell resumed } ] by ','", "ready");
    }

    @Test
    void asynchronousFlowDoesNotHangWhenExecutorRejects() {
        for (String source : Arrays.asList("seq [ gate *after ]", "for entry in gate then *after",
                "while gate then { *after\nbreak }", "join [ gate *after ] by ','")) {
            ScopeImpl scope = new ScopeImpl("reject-flow");
            StatementRegistry registry = new StatementRegistry();
            CompletableFuture<Object> gate = new CompletableFuture<Object>();
            AwaitLifecycleTest.register(registry, scope, gate);
            KetherScriptEngine engine = new KetherScriptEngine(registry, null, task -> {
                throw new java.util.concurrent.RejectedExecutionException("stopped");
            });
            CompletableFuture<Object> result = engine.eval(source, ScriptContext.builder().build()).toCompletableFuture();
            gate.complete(Boolean.TRUE);
            assertTrue(result.isDone(), source);
            assertThrows(CompletionException.class, result::join);
            scope.close();
        }
    }

    private static void verify(String source, Object value) throws Exception {
        ScopeImpl scope = new ScopeImpl("flow");
        StatementRegistry registry = new StatementRegistry();
        CompletableFuture<Object> gate = new CompletableFuture<Object>();
        AwaitLifecycleTest.register(registry, scope, gate);
        ScopedScriptRuntimeTest.Harness host = new ScopedScriptRuntimeTest.Harness();
        KetherScriptEngine engine = new KetherScriptEngine(registry, null, host);
        CompletableFuture<Object> result = engine.eval(source, host.context()).toCompletableFuture();
        Thread worker = new Thread(() -> gate.complete(value), "source-completion-worker");
        worker.start(); worker.join();
        assertTrue(host.messages.isEmpty());
        host.drain();
        result.get(1, TimeUnit.SECONDS);
        assertEquals(Arrays.asList("resumed"), host.messages);
        scope.close();
    }
}
