package me.kzheart.klib.script;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class DynamicSenderTest {
    @Test void variableSourceDoesNotInitializeOrFallbackToFixedSender() {
        ScriptContext context = ScriptContext.builder().sender("fixed").senderVariable("actor").build();
        assertFalse(context.sender().isPresent());
        context.setVariable("actor", "first");
        assertEquals("first", context.sender().get());
        ScriptContext namespaced = context.withNamespaces("custom");
        context.setVariable("actor", "second");
        assertEquals("second", namespaced.sender().get());
        context.removeVariable("actor");
        assertFalse(namespaced.sender().isPresent());
        assertFalse(ScriptContext.builder().sender("fixed").senderVariable("actor")
                .variable("actor", null).build().sender().isPresent());
    }

    @Test void nativeWritesAffectSubsequentServicesBeforeHostWriteback() {
        List<Object> targets = new ArrayList<Object>();
        ScriptContext context = ScriptContext.builder().sender("unused").senderVariable("actor")
                .variable("actor", "initial")
                .service(MessageSink.class, (target, message) -> targets.add(target)).build();
        KetherScriptEngine engine = new KetherScriptEngine(new StatementRegistry());
        engine.evalChecked("tell first; set actor to changed; tell second; set actor to null; tell third", context)
                .toCompletableFuture().join();
        assertEquals(Arrays.asList("initial", "changed", null), targets);
        assertFalse(context.sender().isPresent());
    }

    @Test void fixedSenderRemainsUnaffectedByVariablesUnlessOptedIn() {
        List<Object> targets = new ArrayList<Object>();
        ScriptContext context = ScriptContext.builder().sender("fixed")
                .service(MessageSink.class, (target, message) -> targets.add(target)).build();
        new KetherScriptEngine(new StatementRegistry()).evalChecked("set actor to changed; tell hello", context)
                .toCompletableFuture().join();
        assertEquals(Arrays.asList("fixed"), targets);
        assertThrows(IllegalArgumentException.class, () -> ScriptContext.builder().senderVariable(" "));
    }
}
