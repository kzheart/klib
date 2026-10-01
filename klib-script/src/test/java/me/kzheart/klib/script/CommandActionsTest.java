package me.kzheart.klib.script;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import me.kzheart.klib.scope.ScopeImpl;
import me.kzheart.klib.script.kether.core.QuestAction;
import me.kzheart.klib.script.kether.core.QuestActionParser;
import me.kzheart.klib.script.kether.core.QuestContext;
import org.junit.jupiter.api.Test;

class CommandActionsTest {
    private final StatementRegistry registry = new StatementRegistry();
    private final KetherScriptEngine engine = new KetherScriptEngine(registry);
    private final List<String> calls = new ArrayList<String>();
    private final Object player = new Object();
    private final ScriptContext context = ScriptContext.builder().sender(player).variable("level", 5)
            .variable("cmd", "say variable")
            .service(CommandSink.class, (sender, command) -> {
                assertTrue(sender == null || sender == player);
                calls.add((sender == null ? "console:" : "player:") + command);
                return Boolean.TRUE;
            }).service(MessageSink.class, (sender, message) -> calls.add("tell:" + message)).build();

    @Test void evaluatesTheExactLevelRewardSyntaxBeforeDispatch() {
        assertEquals(Boolean.TRUE, eval("command inline *\"scoreboard players add ChemdahLevelProbe probe_x {{ &level }}\" as console"));
        assertEquals(Arrays.asList("console:scoreboard players add ChemdahLevelProbe probe_x 5"), calls);
    }

    @Test void preservesInlineWithoutStarAndNestedExpressions() {
        assertEquals(Boolean.TRUE, eval("command inline \"say {{ calc 'level + 1' }}\" as console"));
        assertEquals(Arrays.asList("console:say 6"), calls);
    }

    @Test void acceptsVariablesAndBlocksAndKeepsFollowingStatements() {
        assertEquals("after", eval("command &cmd as console; command { set cmd changed; &cmd } as player\ntell after"));
        assertEquals(Arrays.asList("console:say variable", "player:changed", "tell:after"), calls);
        assertEquals("changed", context.variableOrNull("cmd"));
    }

    @Test void preservesLegacyBareCommandsAndTheirStatementBoundaries() {
        assertEquals("done", eval("command say ready; command say ${level} as console\ntell done"));
        assertEquals(Arrays.asList("player:say ready", "console:say 5", "tell:done"), calls);
    }

    @Test void quotedActionNamesRemainCommandTextAndSenderIsOptional() {
        assertEquals("next", eval("command \"inline\" as console; command inline *\"say player\"\ntell next"));
        assertEquals(Arrays.asList("console:inline", "player:say player", "tell:next"), calls);
    }

    @Test void malformedNestedActionsAndMissingSenderDoNotDispatch() {
        assertThrows(CompletionException.class, () -> eval("command inline calc as console"));
        assertThrows(CompletionException.class, () -> eval("command inline *\"say ready\" as"));
        assertTrue(calls.isEmpty());
    }

    @Test void customCommandRegistrationsKeepTheirExistingParser() {
        ScopeImpl scope = ScopeImpl.create("custom-command", current -> registry.register(current, "klib", "command",
                (call, context) -> CompletableFuture.completedFuture(call.arguments().toString())));
        try {
            assertEquals("[inline, quoted, as, console]", eval("command inline quoted as console"));
            assertTrue(calls.isEmpty());
        } finally { scope.close(); }
        assertEquals(Boolean.TRUE, eval("command inline *\"say restored\" as console"));
        assertEquals(Arrays.asList("console:say restored"), calls);
    }

    @Test void aCustomNamespaceCommandDoesNotUseTheBuiltinCommandSink() {
        ScopeImpl scope = ScopeImpl.create("namespaced-command", current -> registry.register(current, "custom", "command",
                (call, context) -> CompletableFuture.completedFuture(call.arguments().toString())));
        try {
            ScriptContext selected = context.withNamespaces("custom");
            assertEquals("[inline, literal, as, console]", engine.eval("command inline literal as console", selected)
                    .toCompletableFuture().join());
            assertTrue(calls.isEmpty());
        } finally { scope.close(); }
    }

    @Test void asyncCommandValueResumesOnTheHostExecutorBeforeDispatch() {
        CompletableFuture<Object> value = new CompletableFuture<Object>();
        ArrayDeque<Runnable> scheduled = new ArrayDeque<Runnable>();
        ScopeImpl scope = ScopeImpl.create("command-value", current -> registry.registerKether(current, "klib", "command-value",
                QuestActionParser.of(reader -> new QuestAction<Object>() {
                    @Override public CompletableFuture<Object> process(QuestContext.Frame frame) { return value; }
                })));
        try {
            KetherScriptEngine asyncEngine = new KetherScriptEngine(registry, null, scheduled::add);
            CompletableFuture<Object> result = asyncEngine.eval("set level 7\ncommand inline command-value as console\ntell resumed", context).toCompletableFuture();
            assertFalse(result.isDone());
            assertTrue(calls.isEmpty());
            value.complete("say async {{ &level }}");
            assertTrue(calls.isEmpty(), "command must wait for its host executor");
            while (!scheduled.isEmpty()) scheduled.remove().run();
            assertEquals("resumed", result.join());
            assertEquals(Arrays.asList("console:say async 7", "tell:resumed"), calls);
        } finally { scope.close(); }
    }

    private Object eval(String source) { return engine.eval(source, context).toCompletableFuture().join(); }
}
