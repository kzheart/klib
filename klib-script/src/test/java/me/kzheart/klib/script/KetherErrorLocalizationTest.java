package me.kzheart.klib.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletionException;
import me.kzheart.klib.scope.ScopeImpl;
import org.junit.jupiter.api.Test;

class KetherErrorLocalizationTest {

    @Test
    void unknownStatementReportsChineseSourceLocation() {
        KetherScriptEngine engine = new KetherScriptEngine(new StatementRegistry());

        ScriptException failure = failure(engine, "# comment\nmissing_action");

        assertEquals("unknown-statement", failure.code());
        assertEquals(2, failure.line());
        assertEquals(1, failure.column());
        assertTrue(failure.getMessage().contains("第 2 行第 1 列"));
        assertTrue(failure.getMessage().contains("missing_action"));
    }

    @Test
    void actionFailureRetainsCauseAndUsesRequestedLocale() {
        StatementRegistry registry = new StatementRegistry();
        IllegalStateException cause = new IllegalStateException("boom");
        ScopeImpl scope = ScopeImpl.create("errors", current ->
                registry.register(current, "explode", (call, context) -> {
                    throw cause;
                }));
        KetherScriptEngine engine = new KetherScriptEngine(registry);

        ScriptException failure = failure(
                engine,
                "explode",
                ScriptContext.builder().locale(Locale.ENGLISH).build());

        assertEquals("action-failed", failure.code());
        assertTrue(failure.getMessage().contains("Line 1, column 1"));
        assertTrue(failure.getMessage().contains("explode"));
        assertSame(cause, failure.getCause());
        scope.close();
    }

    @Test
    void runtimeFailureReportsActualStatementLineColumnAndName() {
        StatementRegistry registry = new StatementRegistry();
        ScopeImpl scope = ScopeImpl.create("runtime-location", current ->
                registry.register(current, "explode", (call, context) -> {
                    throw new IllegalStateException("boom");
                }));
        KetherScriptEngine engine = new KetherScriptEngine(registry);

        ScriptException failure = failure(
                engine,
                "literal ok\n    explode",
                ScriptContext.builder().locale(Locale.ENGLISH).build());

        assertEquals("action-failed", failure.code());
        assertEquals(2, failure.line());
        assertEquals(5, failure.column());
        assertTrue(failure.getMessage().contains("Line 2, column 5"));
        assertTrue(failure.getMessage().contains("explode"));
        scope.close();
    }

    @Test
    void nestedEvaluationHasBoundedDepth() {
        StatementRegistry registry = new StatementRegistry();
        ScopeImpl scope = ScopeImpl.create("recursion-limit", current ->
                registry.register(current, "recurse", (call, context) ->
                        call.eval("recurse", context)));
        KetherScriptEngine engine = new KetherScriptEngine(registry);

        ScriptException failure = failure(engine, "recurse");

        assertEquals("action-failed", failure.code());
        assertTrue(failure.getMessage().contains("nesting exceeds"));
        scope.close();
    }

    @Test
    void customStatementArgumentsStopAtSourceLineBoundary() {
        StatementRegistry registry = new StatementRegistry();
        List<List<String>> calls = new ArrayList<List<String>>();
        ScopeImpl scope = ScopeImpl.create("line-boundary", current ->
                registry.register(current, "capture", (call, context) -> {
                    calls.add(call.arguments());
                    return java.util.concurrent.CompletableFuture.completedFuture(null);
                }));

        new KetherScriptEngine(registry).eval(
                "capture first second\ncapture third",
                ScriptContext.builder().build()).toCompletableFuture().join();

        assertEquals(Arrays.asList(
                Arrays.asList("first", "second"),
                Arrays.asList("third")), calls);
        scope.close();
    }

    private static ScriptException failure(KetherScriptEngine engine, String source) {
        return failure(engine, source, ScriptContext.builder().build());
    }

    private static ScriptException failure(
            KetherScriptEngine engine,
            String source,
            ScriptContext context
    ) {
        try {
            engine.eval(source, context).toCompletableFuture().join();
            throw new AssertionError("Expected script evaluation to fail");
        } catch (CompletionException failure) {
            return (ScriptException) failure.getCause();
        }
    }
}
