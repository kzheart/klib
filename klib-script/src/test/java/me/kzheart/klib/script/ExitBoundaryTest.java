package me.kzheart.klib.script;

import java.util.concurrent.atomic.AtomicInteger;
import me.kzheart.klib.scope.ScopeImpl;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ExitBoundaryTest {
    @ParameterizedTest
    @ValueSource(strings = {"all", "any", "array"})
    void explicitExitPreventsFollowingActionsInsideSynchronousGroups(String group) {
        for (boolean scoped : new boolean[]{false, true}) {
            ScopeImpl scope = new ScopeImpl("exit-boundary");
            try {
                StatementRegistry registry = new StatementRegistry();
                KetherScriptEngine engine = scoped
                        ? new ScopedScriptRuntime(scope, registry, null, Runnable::run, true).engine()
                        : new KetherScriptEngine(registry, null, Runnable::run, true);
                AtomicInteger effects = new AtomicInteger();
                ScriptContext context = ScriptContext.builder()
                        .service(JavaScriptEvaluator.class, (source, variables) -> { effects.incrementAndGet(); return true; })
                        .service(MessageSink.class, (sender, text) -> effects.incrementAndGet()).build();
                engine.evalChecked(group + " [ exit js 'sideEffect()' ]\ntell forbidden", context)
                        .toCompletableFuture().join();
                assertEquals(0, effects.get(), group + ", scoped=" + scoped);
            } finally { scope.close(); }
        }
    }
}
