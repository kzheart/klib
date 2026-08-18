package me.kzheart.klib.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import me.kzheart.klib.scope.ScopeImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class KetherRuntimeSafetyTest {

    @Test
    @Timeout(5)
    void completionDoesNotOverwriteConcurrentContextUpdates() throws Exception {
        StatementRegistry registry = new StatementRegistry();
        ScopeImpl scope = new ScopeImpl("concurrent-update");
        CompletableFuture<Object> gate = new CompletableFuture<Object>();
        registry.register(scope, "gate", (call, context) -> gate);
        KetherScriptEngine engine = new KetherScriptEngine(registry, null, Runnable::run);
        ScriptContext context = ScriptContext.builder().variable("value", "initial").build();

        CompletableFuture<Object> execution = engine.eval("gate", context).toCompletableFuture();
        context.setVariable("value", "concurrent");
        gate.complete("done");

        assertEquals("done", execution.get(2, TimeUnit.SECONDS));
        assertEquals("concurrent", context.variableOrNull("value"));
        scope.close();
    }

    @Test
    void sourceLengthBudgetFailsAsAControlledScriptException() {
        StringBuilder source = new StringBuilder();
        while (source.length() <= 300 * 1024) {
            source.append('x');
        }

        ScriptException failure = evaluateFailure(source.toString());

        assertEquals("compilation-limit", failure.code());
    }

    @Test
    void actionCountBudgetFailsAsAControlledScriptException() {
        StringBuilder source = new StringBuilder();
        for (int index = 0; index < 10_100; index++) {
            source.append("literal x\n");
        }

        ScriptException failure = evaluateFailure(source.toString());

        assertEquals("compilation-limit", failure.code());
    }

    @Test
    void syntaxDepthBudgetFailsAsAControlledScriptException() {
        StringBuilder source = new StringBuilder();
        for (int index = 0; index < 140; index++) {
            source.append("namespace klib { ");
        }
        source.append("literal ok");
        for (int index = 0; index < 140; index++) {
            source.append(" }");
        }

        ScriptException failure = evaluateFailure(source.toString());

        assertEquals("compilation-limit", failure.code());
    }

    @Test
    void invalidSyntaxIsReturnedThroughTheEvaluationStage() {
        ScriptException failure = evaluateFailure("namespace quest {");

        assertEquals("action-failed", failure.code());
    }

    @Test
    void statementStackOverflowIsConvertedToAControlledFailure() {
        StatementRegistry registry = new StatementRegistry();
        ScopeImpl scope = new ScopeImpl("stack-overflow");
        registry.register(scope, "explode", (call, context) -> {
            throw new StackOverflowError("simulated parser recursion");
        });
        KetherScriptEngine engine = new KetherScriptEngine(registry);

        ScriptException failure = evaluateFailure(engine, "explode");

        assertEquals("action-failed", failure.code());
        assertInstanceOf(StackOverflowError.class, failure.getCause());
        scope.close();
    }

    private static ScriptException evaluateFailure(String source) {
        return evaluateFailure(new KetherScriptEngine(new StatementRegistry()), source);
    }

    private static ScriptException evaluateFailure(KetherScriptEngine engine, String source) {
        CompletableFuture<Object> result = engine.eval(
                source, ScriptContext.builder().build()).toCompletableFuture();
        ExecutionException execution = assertThrows(
                ExecutionException.class,
                () -> result.get(2, TimeUnit.SECONDS));
        return assertInstanceOf(ScriptException.class, execution.getCause());
    }
}
