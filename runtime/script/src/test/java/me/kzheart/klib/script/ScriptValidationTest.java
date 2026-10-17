package me.kzheart.klib.script;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import me.kzheart.klib.scope.ScopeImpl;
import org.junit.jupiter.api.Test;

class ScriptValidationTest {
    @Test
    void validatesWithoutExecutingActionsOrHostServicesEvenWithDuplicateMain() {
        ScopeImpl scope = new ScopeImpl("validate");
        StatementRegistry registry = new StatementRegistry();
        AtomicInteger actions = new AtomicInteger();
        registry.register(scope, "side-effect", (call, context) -> {
            actions.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        });
        KetherScriptEngine engine = new KetherScriptEngine(registry);
        ScriptContext context = ScriptContext.builder().service(MessageSink.class, (sender, text) -> {
            throw new AssertionError("Validation called a host service");
        }).build();
        engine.validate("def main = { tell forbidden }\ndef main = { side-effect }", context);
        engine.validate("def main = { wait 1s }\ndef untouched = { tell forbidden }", context);
        assertEquals(0, actions.get());
        assertEquals(0, context.variables().size());
        scope.close();
    }

    @Test
    void validatesUncalledBlocksAndReportsLocalizedCompilationFailure() {
        KetherScriptEngine engine = new KetherScriptEngine(new StatementRegistry());
        ScriptException failure = assertThrows(ScriptException.class,
                () -> engine.validate("def main = { *ok }\ndef unreachable = { no-such-action }", ScriptContext.builder().build()));
        assertEquals("unknown-statement", failure.code());
    }

    @Test
    void leadingCommentsBeforeNamedBlocksAreRecognizedWithoutScanningQuotedText() {
        KetherScriptEngine engine = new KetherScriptEngine(new StatementRegistry());
        ScriptContext context = ScriptContext.builder().build();
        String named = "  # setup\n// named entry\n def\tmain = { *ready }\ndef unused = { *unused }";
        engine.validate(named, context);
        assertEquals("ready", engine.evalChecked(named, context).toCompletableFuture().join());
        String literal = "*'def main = { forbidden }'";
        engine.validate(literal, context);
        assertEquals("def main = { forbidden }", engine.evalChecked(literal, context).toCompletableFuture().join());
    }

    @Test
    void validationSharesExecutionCacheAndScopedValidationDoesNotTrackExecutions() {
        ScopeImpl scope = new ScopeImpl("scoped-validation");
        ScopedScriptRuntime runtime = new ScopedScriptRuntime(scope, new StatementRegistry(), null, Runnable::run, false);
        ScriptContext context = ScriptContext.builder().build();
        runtime.validate("*ready", context);
        long compiled = runtime.engine().compilationCount();
        assertEquals(0, runtime.activeExecutionCount());
        assertEquals("ready", runtime.evalChecked("*ready", context).toCompletableFuture().join());
        assertEquals(compiled, runtime.engine().compilationCount());
        scope.close();
        assertThrows(IllegalStateException.class, () -> runtime.validate("*ready", context));
    }
}
