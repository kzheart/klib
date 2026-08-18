package me.kzheart.klib.script;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.CompletableFuture;
import me.kzheart.klib.scope.ScopeImpl;
import me.kzheart.klib.script.kether.core.QuestAction;
import me.kzheart.klib.script.kether.core.QuestActionParser;
import me.kzheart.klib.script.kether.core.QuestContext;
import org.junit.jupiter.api.Test;

class KetherRegistrationTest {

    @Test
    void fullKetherParserRunsLocallyWithoutChangingLegacyRegistration() {
        ScopeImpl scope = new ScopeImpl("test");
        StatementRegistry registry = new StatementRegistry();
        registry.registerKether(scope, "custom", "echo", QuestActionParser.of(reader -> {
            final String value = reader.nextToken();
            return literal(value);
        }));

        Object value = new KetherScriptEngine(registry).eval(
                "echo hello",
                ScriptContext.builder().namespaces("custom").build())
                .toCompletableFuture().join();

        assertEquals("hello", value);
        scope.close();
    }

    static QuestAction<Object> literal(final Object value) {
        return new QuestAction<Object>() {
            @Override
            public CompletableFuture<Object> process(QuestContext.Frame frame) {
                return CompletableFuture.completedFuture(value);
            }
        };
    }
}
