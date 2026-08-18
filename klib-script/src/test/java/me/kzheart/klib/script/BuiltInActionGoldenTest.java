package me.kzheart.klib.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import me.kzheart.klib.scope.ScopeImpl;
import org.junit.jupiter.api.Test;

class BuiltInActionGoldenTest {

    @Test
    void variablesInlineMathLogicAndBranchesMatchGoldenValues() {
        KetherScriptEngine engine = new KetherScriptEngine(new StatementRegistry());
        ScriptContext context = ScriptContext.builder().build();

        assertEquals("hello-Klib", eval(engine,
                "set product Klib; inline hello-${product}", context));
        assertEquals(new BigDecimal("15"), eval(engine, "add 10 2 3", context));
        assertTrue(condition(engine, "and true ${product}", context));
        assertFalse(condition(engine, "or false 0", context));
        assertEquals("accepted", eval(engine,
                "if { gte 5 3 } then { literal accepted } else { literal denied }",
                context));
    }

    @Test
    void existingVariablesAreNotRestoredFromTheInitialKetherFrame() {
        KetherScriptEngine engine = new KetherScriptEngine(new StatementRegistry());
        ScriptContext context = ScriptContext.builder()
                .variable("value", "before")
                .variable("removed", "before")
                .build();

        assertEquals("after", eval(engine, "set value after\n&value", context));
        assertEquals("after", context.variableOrNull("value"));
        assertNull(eval(engine, "unset removed\n&removed", context));
        assertFalse(context.variable("removed").isPresent());
    }

    @Test
    void namespaceCombinationAndHostServicesCompose() {
        StatementRegistry registry = new StatementRegistry();
        KetherScriptEngine engine = new KetherScriptEngine(registry);
        ScopeImpl scope = ScopeImpl.create("custom", current -> {
            registry.register(current, "reward", (call, context) ->
                    CompletableFuture.completedFuture("global"));
            registry.register(current, "quest", "reward", Statements.combine()
                    .required("player")
                    .optional("amount", "1")
                    .execute((arguments, context) -> CompletableFuture.completedFuture(
                            arguments.require("player") + ':' + arguments.value("amount", context))));
        });
        List<String> messages = new ArrayList<String>();
        AtomicReference<String> command = new AtomicReference<String>();
        ScriptContext context = ScriptContext.builder()
                .sender("console")
                .service(MessageSink.class, (sender, message) -> messages.add(sender + ":" + message))
                .service(CommandSink.class, (sender, value) -> {
                    command.set(sender + ":" + value);
                    return true;
                })
                .service(PlaceholderResolver.class, (sender, value) -> value.replace("%name%", "Alex"))
                .service(DelayScheduler.class, duration -> CompletableFuture.completedFuture(duration))
                .build();

        assertEquals("global", eval(engine, "reward Alex 2", context).toString());
        assertEquals("Alex:2", eval(engine, "quest:reward Alex 2", context).toString());
        assertEquals("Alex:1", eval(engine, "namespace quest { reward Alex }", context).toString());
        assertEquals("global", eval(engine,
                "namespace quest { namespace global { reward } }", context).toString());
        assertEquals("hi Alex", eval(engine, "papi hi %name%", context));
        assertEquals("hello", eval(engine, "tell hello", context));
        assertEquals("console:hello", messages.get(0));
        assertEquals(Boolean.TRUE, eval(engine, "command say ready", context));
        assertEquals("console:say ready", command.get());
        assertEquals(Duration.ofMillis(100L), eval(engine, "delay 2t", context));
        scope.close();
    }

    private static Object eval(
            KetherScriptEngine engine,
            String source,
            ScriptContext context
    ) {
        return engine.eval(source, context).toCompletableFuture().join();
    }

    private static boolean condition(
            KetherScriptEngine engine,
            String source,
            ScriptContext context
    ) {
        return engine.evalCondition(source, context).toCompletableFuture().join().booleanValue();
    }
}
