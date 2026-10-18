package me.kzheart.klib.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import me.kzheart.klib.scope.ScopeImpl;
import me.kzheart.klib.script.kether.core.ParsedAction;
import me.kzheart.klib.script.kether.core.QuestAction;
import me.kzheart.klib.script.kether.core.QuestActionParser;
import me.kzheart.klib.script.kether.core.QuestContext;
import org.junit.jupiter.api.Test;

class KetherRegistrationTest {

    @Test
    void customReaderLiteralKeepsIsolatedTokenValidation() {
        ScopeImpl scope = new ScopeImpl("literal-test");
        try {
            StatementRegistry registry = new StatementRegistry();
            registry.registerKether(scope, "custom", "raw", QuestActionParser.of(
                    reader -> QuestAction.unquotedLiteral(reader.nextToken())));
            registry.registerKether(scope, "custom", "wrap", QuestActionParser.of(reader -> {
                final ParsedAction<?> nested = reader.nextParsedAction();
                return new QuestAction<Object>() {
                    @Override
                    public CompletableFuture<Object> process(QuestContext.Frame frame) {
                        return frame.newFrame(nested).run();
                    }
                };
            }));
            KetherScriptEngine engine = new KetherScriptEngine(registry, null, Runnable::run, true);
            ScriptContext context = ScriptContext.builder().namespaces("custom").build();
            assertEquals("value", engine.eval("raw value", context).toCompletableFuture().join());
            assertEquals("nested", engine.eval("wrap raw nested", context).toCompletableFuture().join());
            RuntimeException failure = assertThrows(RuntimeException.class,
                    () -> engine.eval("raw first raw second", context).toCompletableFuture().join());
            StringBuilder messages = new StringBuilder();
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) messages.append(cause.getMessage());
            assertTrue(messages.toString().contains("isolated-literal"));
            assertThrows(NullPointerException.class, () -> QuestAction.unquotedLiteral(null));
        } finally {
            scope.close();
        }
    }

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

    @Test
    void parserCanReadRawSourceOfFollowingAction() {
        ScopeImpl scope = new ScopeImpl("test");
        StatementRegistry registry = new StatementRegistry();
        registry.registerKether(scope, "custom", "echo", QuestActionParser.of(reader -> literal(reader.nextToken())));
        registry.registerKether(scope, "custom", "raw", QuestActionParser.of(reader -> {
            int begin = reader.getIndex();
            reader.nextParsedAction();
            return literal(reader.source(begin, reader.getIndex()).trim());
        }));

        Object value = new KetherScriptEngine(registry).eval(
                "raw { echo hello }",
                ScriptContext.builder().namespaces("custom").build())
                .toCompletableFuture().join();

        assertEquals("{ echo hello }", value);
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
