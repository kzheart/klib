package me.kzheart.klib.script;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import me.kzheart.klib.scope.ScopeImpl;
import me.kzheart.klib.script.kether.core.QuestAction;
import me.kzheart.klib.script.kether.core.QuestActionParser;
import me.kzheart.klib.script.kether.core.QuestContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class NativeCheckActionsTest {
    private final StatementRegistry registry = new StatementRegistry();
    private final KetherScriptEngine engine;

    NativeCheckActionsTest() {
        NativeCheckActions.install(registry);
        engine = new KetherScriptEngine(registry);
    }
    private Object eval(String source) { return eval(source, ScriptContext.builder().build()); }
    private Object eval(String source, ScriptContext context) { return engine.eval(source, context).toCompletableFuture().join(); }

    @Test void standaloneBooleansAreOriginalStringLiteralsAndNullIsNull() {
        assertEquals("true", eval("true"));
        assertEquals("false", eval("false"));
        assertNull(eval("null"));
        assertEquals("yes", eval("if true then { *yes } else { *no }"));
        assertEquals("no", eval("if false then { *yes } else { *no }"));
        assertEquals("no", eval("if null then { *yes } else { *no }"));
        assertEquals(Boolean.TRUE, eval("check null is &missing"));
        assertEquals(Boolean.FALSE, eval("check null is *null"));
    }

    @Test void defaultGameConditionAndChooseComparisonConsumeExactlyTwoActions() {
        ScriptContext context = ScriptContext.builder().variable("choose", 3).variable("number", 2).build();
        assertEquals(Boolean.FALSE, eval("check &game not null", context));
        eval("set game true", context);
        assertEquals(Boolean.TRUE, eval("check &game not null", context));
        assertEquals("chosen", eval("if check &choose is null then { *missing } else { *chosen }", context));
        assertNull(eval("set win to check &choose > &number", context));
        assertEquals(Boolean.TRUE, context.variableOrNull("win"));
        assertEquals("winner", eval("if &win then { *winner } else { *loser }", context));
        assertEquals("after", eval("check &choose > &number\n*after", context));
    }

    @Test void bothOperandsAcceptNestedParsedActionsAndFrameVariableWrites() {
        assertEquals(Boolean.TRUE, eval("check math add [ 1 2 ] is round math div [ 9 3 ]"));
        assertEquals(Boolean.TRUE, eval("check check 1 is 1 is true"));
        ScriptContext context = ScriptContext.builder().build();
        assertEquals(Boolean.TRUE, eval("check { set number to round 1.5\n&number } is &number", context));
        assertEquals(Integer.valueOf(2), context.variableOrNull("number"));
    }

    @Test void everyOriginalOperatorAliasRetainsItsComparison() {
        for (String operator : Arrays.asList("=", "==", "is")) assertEquals(Boolean.TRUE, eval("check 1 " + operator + " 1.0"));
        for (String operator : Arrays.asList("!=", "!is", "not")) assertEquals(Boolean.TRUE, eval("check 1 " + operator + " 2"));
        for (String operator : Arrays.asList("=!", "is!")) assertEquals(Boolean.FALSE, eval("check 1 " + operator + " 1.0"));
        for (String operator : Arrays.asList("=!!", "is!!")) assertEquals(Boolean.TRUE, eval("check &missing " + operator + " null"));
        for (String operator : Arrays.asList("=?", "is?")) assertEquals(Boolean.TRUE, eval("check MiXeD " + operator + " mixed"));
        for (String operator : Arrays.asList(">", "gt", ">=", "gte")) assertEquals(Boolean.TRUE, eval("check 2 " + operator + " 1"));
        for (String operator : Arrays.asList("<", "lt", "<=", "lte")) assertEquals(Boolean.TRUE, eval("check 1 " + operator + " 2"));
        for (String operator : Arrays.asList("contains", "has")) assertEquals(Boolean.TRUE, eval("check alphabet " + operator + " pha"));
        assertEquals(Boolean.TRUE, eval("check pha in alphabet"));
        assertThrows(CompletionException.class, () -> eval("check 1 IS 1"));
        assertThrows(CompletionException.class, () -> eval("check 1 unknown 1"));
    }

    @Test void inferredEqualityUsesStrictBooleanParsingAndOriginalDoublePrecision() {
        ScriptContext context = ScriptContext.builder().variable("truth", Boolean.TRUE).variable("negativeZero", -0.0)
                .variable("nan", Double.NaN).variable("integer", 1).variable("double", 1.0).build();
        assertEquals(Boolean.TRUE, eval("check true is &truth", context));
        assertEquals(Boolean.FALSE, eval("check TRUE is &truth", context));
        assertEquals(Boolean.TRUE, eval("check 9007199254740992 is 9007199254740993"));
        assertEquals(Boolean.TRUE, eval("check &negativeZero is 0.0", context));
        assertEquals(Boolean.FALSE, eval("check &nan is &nan", context));
        assertEquals(Boolean.FALSE, eval("check &integer is! &double", context));
        assertEquals(Boolean.TRUE, eval("check &integer is &double", context));
    }

    @Test void identityAndStrictEqualityDoNotInferOrCopyValues() {
        String shared = new String("text"), other = new String("text");
        ScriptContext context = ScriptContext.builder().variable("shared", shared).variable("alias", shared).variable("other", other).build();
        assertEquals(Boolean.TRUE, eval("check &shared is!! &alias", context));
        assertEquals(Boolean.FALSE, eval("check &shared is!! &other", context));
        assertEquals(Boolean.TRUE, eval("check &shared is! &other", context));
        assertEquals(Boolean.TRUE, eval("check null is? *NULL", context));
    }

    @Test void numericOrderUsesCoerceSanitisationAndUnparseableZeroRatherThanLexicalOrder() {
        assertEquals(Boolean.FALSE, eval("check zebra > apple"));
        assertEquals(Boolean.TRUE, eval("check rubbish >= null"));
        assertEquals(Boolean.TRUE, eval("check '1,000' > 999"));
        assertEquals(Boolean.TRUE, eval("check '(9.5, 10.6, 33.2)' < 10"));
        assertEquals(Boolean.TRUE, eval("check '1-2' <= 0"));
        assertEquals(Boolean.FALSE, eval("check NaN > 0"));
        assertEquals(Boolean.TRUE, eval("check NaNd >= 0"));
        assertEquals(Boolean.TRUE, eval("check Infinity > 0"));
        assertEquals(Boolean.TRUE, eval("check Infinityd <= 0"));
        assertEquals(Boolean.TRUE, eval("check '0x1.0p3' > 7"));
    }

    @Test void containsAndInUseExactElementsArrayElementsAndMapKeys() {
        Map<Object, Object> map = new HashMap<Object, Object>(); map.put("key", "value");
        ScriptContext context = ScriptContext.builder().variable("list", Arrays.asList(1, null))
                .variable("array", new Object[]{"element", null}).variable("map", map).build();
        assertEquals(Boolean.TRUE, eval("check &list contains { math add [ 1 ] }", context));
        assertEquals(Boolean.FALSE, eval("check &list contains *1", context));
        assertEquals(Boolean.TRUE, eval("check null in &list", context));
        assertEquals(Boolean.TRUE, eval("check element in &array", context));
        assertEquals(Boolean.TRUE, eval("check &array contains null", context));
        assertEquals(Boolean.TRUE, eval("check &map has key", context));
        assertEquals(Boolean.FALSE, eval("check value in &map", context));
        assertEquals(Boolean.TRUE, eval("check null contains *ull", context));
    }

    @Test void readOnlyPlayerAdapterSuppliesDefaultLevelWithoutEatingTheOperator() {
        ScriptContext context = playerContext("Alex", key -> "level".equals(key) ? 5 : "Alex", null);
        assertEquals(Integer.valueOf(5), eval("player level", context));
        assertEquals(Boolean.TRUE, eval("check player level < 10", context));
        assertEquals(Boolean.TRUE, eval("check player level = 5", context));
        assertEquals("low", eval("if check player level < 10 then { *low } else { *high }", context));
        assertEquals(Boolean.TRUE, eval("check player name is sender", context));
        assertEquals("after", eval("player level\n*after", context));
        assertThrows(CompletionException.class, () -> eval("player level to 9", context));
        assertThrows(CompletionException.class, () -> eval("player level = 9", context));
        assertThrows(CompletionException.class, () -> eval("player level increase 1", context));
    }

    @Test void playerRejectsNonPlayersUnknownPropertiesAndMissingServices() {
        assertThrows(CompletionException.class, () -> eval("player level"));
        for (String sender : Arrays.asList("console", "command-block")) {
            assertThrows(CompletionException.class, () -> eval("player level", playerContext(sender, key -> 5, null)));
        }
        assertThrows(CompletionException.class, () -> eval("player unsupported", playerContext("Alex", key -> null, null)));
        assertThrows(CompletionException.class, () -> eval("player level", ScriptContext.builder().sender("Alex").build()));
    }

    @Test void papiConsumesNestedActionAndLeavesComparisonAndNextStatementIntact() {
        List<String> calls = new ArrayList<String>();
        ScriptContext context = playerContext("Alex", key -> 5, (sender, text) -> { calls.add(text); return "5"; });
        assertEquals(Boolean.TRUE, eval("check papi inline '%{{ *level }}%' is player level", context));
        assertEquals(Collections.singletonList("%level%"), calls);
        assertEquals("after", eval("placeholder '%level%'\n*after", context));
        assertEquals("5", eval("papi null", context));
        assertEquals("", calls.get(calls.size() - 1));
    }

    @Test void papiTrimsMultilineIndentWithoutRemovingTrailingSpaces() {
        ScriptContext context = playerContext("Alex", key -> 5, (sender, text) -> text);
        assertEquals("first  \n  second", eval("papi '\n    first  \n      second\n'", context));
        assertEquals("same  ", eval("papi '    same  '", context));
        assertEquals("  ", eval("papi '\n  \n'", context));
        assertEquals("a\nb", eval("papi '\r\n a\r b\r\n'", context));
    }

    @Test void papiRejectsNonPlayersAndMissingResolverRatherThanPretendingExpanded() {
        for (String sender : Arrays.asList("console", "command-block")) {
            assertThrows(CompletionException.class, () -> eval("papi '%level%'", playerContext(sender, key -> 5, (target, text) -> text)));
        }
        assertThrows(CompletionException.class, () -> eval("papi '%level%'"));
        assertThrows(CompletionException.class, () -> eval("papi '%level%'", playerContext("Alex", key -> 5, null)));
    }

    @Test void papiOriginalStrFallbackRetriesEmptyAfterInputOrFirstCallbackFailure() {
        List<String> inputs = new ArrayList<String>();
        ScriptContext context = playerContext("Alex", key -> 5, (sender, text) -> {
            inputs.add(text); if (!text.isEmpty()) throw new IllegalArgumentException("first callback"); return "fallback";
        });
        assertEquals("fallback", eval("papi '%level%'", context));
        assertEquals(Arrays.asList("%level%", ""), inputs);
        inputs.clear();
        assertEquals("fallback", eval("papi { math rubbish + 1 }", context));
        assertEquals(Collections.singletonList(""), inputs);
        assertThrows(CompletionException.class, () -> eval("papi '%level%'", playerContext("Alex", key -> 5,
                (sender, text) -> { throw new IllegalStateException("always fails"); })));
    }

    @Test void checkConvertsSynchronousLeftFailureToFutureForEnclosingPapiFallback() {
        List<String> calls = new ArrayList<String>();
        ScriptContext context = playerContext("Alex", key -> 5, (sender, text) -> { calls.add(text); return "fallback"; });
        ScopeImpl scope = ScopeImpl.create("check-sync-failure", current ->
                registry.registerKether(current, "klib", "throwing", QuestActionParser.of(reader -> nativeAction(frame -> {
                    throw new IllegalArgumentException("synchronous process failure");
                }))));
        try {
            assertEquals("fallback", eval("papi check throwing is 1", context));
            assertEquals(Collections.singletonList(""), calls);
            calls.clear();
            // 直接 papi 的 input.run 原本在 .str 链外；其同步异常不触发 fallback。
            assertThrows(CompletionException.class, () -> eval("papi throwing", context));
            assertTrue(calls.isEmpty());
        } finally { scope.close(); }
    }

    @Test @Timeout(5) void deferredLeftCompletesBeforeRightAndResumesOnFrameExecutor() throws Exception {
        CompletableFuture<Object> left = new CompletableFuture<Object>();
        List<String> calls = new ArrayList<String>();
        ScopeImpl scope = ScopeImpl.create("check-order", current -> {
            registry.registerKether(current, "klib", "left", QuestActionParser.of(reader -> nativeAction(frame -> {
                calls.add("left"); return left;
            })));
            registry.registerKether(current, "klib", "right", QuestActionParser.of(reader -> nativeAction(frame -> {
                calls.add(Thread.currentThread().getName()); return CompletableFuture.completedFuture("2");
            })));
        });
        ExecutorService executor = Executors.newSingleThreadExecutor(task -> new Thread(task, "check-main"));
        try {
            KetherScriptEngine async = new KetherScriptEngine(registry, null, executor);
            CompletableFuture<Object> result = async.eval("check left is right", ScriptContext.builder().build()).toCompletableFuture();
            assertFalse(result.isDone()); assertEquals(Collections.singletonList("left"), calls);
            left.complete(Integer.valueOf(2));
            assertEquals(Boolean.TRUE, result.get(2, TimeUnit.SECONDS));
            assertEquals(Arrays.asList("left", "check-main"), calls);
        } finally { scope.close(); executor.shutdownNow(); }
    }

    @Test void invalidOperatorFailsAfterBothOperandsAndOperandFailureDoesNotRunRight() {
        List<String> calls = new ArrayList<String>();
        ScopeImpl scope = ScopeImpl.create("check-failure-order", current -> {
            registry.registerKether(current, "klib", "first", QuestActionParser.of(reader -> nativeAction(frame -> {
                calls.add("first"); return CompletableFuture.completedFuture("1");
            })));
            registry.registerKether(current, "klib", "second", QuestActionParser.of(reader -> nativeAction(frame -> {
                calls.add("second"); return CompletableFuture.completedFuture("1");
            })));
        });
        try {
            assertThrows(CompletionException.class, () -> eval("check first unknown second"));
            assertEquals(Arrays.asList("first", "second"), calls);
            calls.clear();
            assertThrows(CompletionException.class, () -> eval("check { math rubbish + 1 } is second"));
            assertTrue(calls.isEmpty());
        } finally { scope.close(); }
    }

    @Test void requiredArgumentsCannotConsumeClosingDelimitersOrHideNestedParserErrors() {
        for (String source : Arrays.asList("check", "check 1", "check 1 is", "check math add [ 1 ] is",
                "if check 1 is }", "math add [ check 1 is ]", "player", "papi", "placeholder", "check papi round is 1")) {
            assertThrows(CompletionException.class, () -> eval(source), source);
        }
    }

    private ScriptContext playerContext(String sender, Function<String, Object> properties, PlaceholderResolver resolver) {
        ScriptContext.Builder builder = ScriptContext.builder().sender(sender)
                .service(ScriptSenderQuery.class, new ScriptSenderQuery() {
                    @Override public boolean isPlayer(Object target) { return "Alex".equals(target); }
                    @Override public String name(Object target) { return String.valueOf(target); }
                }).service(PlayerQuery.class, new PlayerQuery() {
                    @Override public Object property(Object target, String name) { return properties.apply(name); }
                    @Override public boolean hasPermission(Object target, String name) { return false; }
                });
        if (resolver != null) builder.service(PlaceholderResolver.class, resolver);
        return builder.build();
    }
    private static QuestAction<Object> nativeAction(Function<QuestContext.Frame, CompletableFuture<Object>> operation) {
        return new QuestAction<Object>() {
            @Override public CompletableFuture<Object> process(QuestContext.Frame frame) { return operation.apply(frame); }
        };
    }
}
