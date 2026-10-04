package me.kzheart.klib.script;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import me.kzheart.klib.scope.ScopeImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(5)
class NativeLogicalActionsTest {
    @Test
    void parsesNestedChecksAndPermissionActionsWithoutFlattening() {
        final List<String> checked = new ArrayList<String>();
        ScriptContext context = ScriptContext.builder().sender("Alex")
                .service(ScriptSenderQuery.class, new ScriptSenderQuery() {
                    @Override public boolean isPlayer(Object value) { return true; }
                    @Override public String name(Object value) { return "Alex"; }
                })
                .service(PlayerQuery.class, new PlayerQuery() {
                    @Override public Object property(Object sender, String property) { return 10; }
                    @Override public boolean hasPermission(Object sender, String permission) {
                        checked.add(permission); return true;
                    }
                }).build();
        KetherScriptEngine engine = new KetherScriptEngine(new StatementRegistry());
        assertEquals(Boolean.FALSE, eval(engine, "all [ check 1 > 2 permission x ]", context));
        assertEquals(Arrays.asList("x"), checked);
        assertEquals(Boolean.TRUE, eval(engine, "any [ check 1 > 2 all [ check player level > 1 perm vip ] ]", context));
        assertEquals(Arrays.asList("x", "vip"), checked);
        assertEquals(Boolean.TRUE, eval(engine, "any [ true permission *second ]", context));
        assertEquals(Arrays.asList("x", "vip", "second"), checked);
    }

    @Test
    void keepsEmptyAndValueTruthConversionRules() {
        KetherScriptEngine engine = new KetherScriptEngine(new StatementRegistry());
        ScriptContext context = ScriptContext.builder().variable("positive", 2).variable("zero", 0).build();
        assertEquals(Boolean.TRUE, eval(engine, "all [ ]", context));
        assertEquals(Boolean.FALSE, eval(engine, "any [ ]", context));
        assertEquals(Boolean.FALSE, eval(engine, "any [ false 0 null '' &zero ]", context));
        assertEquals(Boolean.TRUE, eval(engine, "all [ yes &positive *anything ]", context));
        assertEquals(Boolean.TRUE, eval(engine, "all [ any [ false true ] check 2 > 1 ]", context));
    }

    @Test
    void asynchronousGroupContinuesOnProvidedExecutorAndDoesNotShortCircuit() throws Exception {
        ScopeImpl scope = new ScopeImpl("logical");
        StatementRegistry registry = new StatementRegistry();
        ScopedScriptRuntimeTest.Harness host = new ScopedScriptRuntimeTest.Harness();
        CompletableFuture<Object> gate = new CompletableFuture<Object>();
        AwaitLifecycleTest.register(registry, scope, gate);
        KetherScriptEngine engine = new KetherScriptEngine(registry, null, host);
        CompletableFuture<Object> result = engine.eval("all [ false gate { tell checked\ntrue } ]", host.context()).toCompletableFuture();
        assertFalse(result.isDone());
        Thread worker = new Thread(() -> gate.complete(true));
        worker.start(); worker.join();
        assertTrue(host.messages.isEmpty());
        host.drain();
        assertEquals(Boolean.FALSE, result.get(1, TimeUnit.SECONDS));
        assertEquals(Arrays.asList("checked"), host.messages);
        scope.close();
    }

    @Test
    void groupCancellationFailsWithoutEvaluatingLaterActions() {
        ScopeImpl scope = new ScopeImpl("cancel-logical");
        StatementRegistry registry = new StatementRegistry();
        CompletableFuture<Object> gate = new CompletableFuture<Object>();
        AwaitLifecycleTest.register(registry, scope, gate);
        ScopedScriptRuntimeTest.Harness host = new ScopedScriptRuntimeTest.Harness();
        KetherScriptEngine engine = new KetherScriptEngine(registry, null, host);
        CompletableFuture<Object> result = engine.eval("any [ gate { tell stale\ntrue } ]", host.context()).toCompletableFuture();
        gate.cancel(false); host.drain();
        assertThrows(CancellationException.class, result::join);
        assertTrue(host.messages.isEmpty());
        scope.close();
    }

    private static Object eval(KetherScriptEngine engine, String source, ScriptContext context) {
        return engine.eval(source, context).toCompletableFuture().join();
    }
}
