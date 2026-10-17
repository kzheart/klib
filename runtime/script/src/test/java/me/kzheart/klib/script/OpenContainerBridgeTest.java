package me.kzheart.klib.script;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OpenContainerBridgeTest {

    @Test
    void unknownStatementFallsThroughContainersInDiscoveryOrder() {
        AtomicInteger calls = new AtomicInteger();
        OpenContainerBridge bridge = new OpenContainerBridge(() -> Arrays.asList(
                (statement, context) -> {
                    calls.incrementAndGet();
                    return CompletableFuture.completedFuture(
                            OpenContainerBridge.RemoteResolution.unresolved());
                },
                (statement, context) -> {
                    calls.incrementAndGet();
                    return CompletableFuture.completedFuture(
                            OpenContainerBridge.RemoteResolution.resolved("shared-ok"));
                }));
        KetherScriptEngine engine = new KetherScriptEngine(new StatementRegistry(), bridge);

        Object value = engine.eval(
                "external_action value",
                ScriptContext.builder().build()).toCompletableFuture().join();

        assertEquals("shared-ok", value);
        assertEquals(2, calls.get());
    }
}
