package me.kzheart.klib.script;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletionException;
import me.kzheart.klib.scope.ScopeImpl;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KetherCompatibilityTest {
    @Test
    void legacyCaseKeepsDefaultUnchangedAndRegistrationIsScoped() {
        StatementRegistry registry = new StatementRegistry();
        KetherScriptEngine engine = new KetherScriptEngine(registry, null, Runnable::run, true);
        String legacy = "case 4 [ when < 3 -> SLOW else -> VERY SLOW ]";
        assertThrows(CompletionException.class, () -> eval(engine, legacy, 4));
        ScopeImpl scope = new ScopeImpl("legacy");
        KetherCompatibility.installLegacyCases(scope, registry);
        assertEquals("VERY SLOW", eval(engine, legacy, 4));
        scope.close();
        assertThrows(CompletionException.class, () -> eval(engine, legacy, 4));
    }

    @Test
    void originalZaphkielMapperCoversAllBranchesWithoutRewritingSource() {
        StatementRegistry registry = new StatementRegistry();
        KetherScriptEngine engine = new KetherScriptEngine(registry, null, Runnable::run, true);
        ScopeImpl scope = new ScopeImpl("legacy");
        try {
            KetherCompatibility.installLegacyCases(scope, registry);
            String source = "case &attack-speed [\n"
                    + "when < 1 -> FAST\nwhen < 2 -> NORMAL\nwhen < 3 -> SLOW\nelse -> VERY SLOW\n]";
            List<String> expected = Arrays.asList("FAST", "NORMAL", "SLOW", "VERY SLOW");
            for (int speed = 0; speed < expected.size(); speed++) {
                assertEquals(expected.get(speed), eval(engine, source, speed));
            }
        } finally { scope.close(); }
    }

    @Test
    void legacyOnlyExtendsGrammarAndKeepsNativeCaseSemantics() {
        StatementRegistry registry = new StatementRegistry();
        KetherScriptEngine engine = new KetherScriptEngine(registry, null, Runnable::run, true);
        ScopeImpl scope = new ScopeImpl("legacy");
        try {
            KetherCompatibility.installLegacyCases(scope, registry);
            assertEquals("match", eval(engine, "case 3 [ when < 2 -> js 'throw new Error()' when 3 -> 'match' else -> other ]", 0));
            assertEquals("fallback", eval(engine, "case 3 [ when 1 -> no else fallback ]", 0));
            assertEquals("else -> VERY SLOW", eval(engine, "case 1 [ when 1 -> 'else -> VERY SLOW' ]", 0));
            assertEquals("nested", eval(engine, "case 4 [ else -> case 1 [ when 1 -> nested ] ]", 0));
            assertThrows(CompletionException.class, () -> eval(engine, "case 1 [ when 1 ??? invalid ]", 0));
        } finally { scope.close(); }
    }

    private Object eval(KetherScriptEngine engine, String source, Object speed) {
        return engine.eval(source, ScriptContext.builder().variable("attack-speed", speed).build())
                .toCompletableFuture().join();
    }
}
