package me.kzheart.klib.script;

import java.math.BigDecimal;
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
import me.kzheart.klib.script.kether.core.ParsedAction;
import me.kzheart.klib.script.kether.core.QuestAction;
import me.kzheart.klib.script.kether.core.QuestActionParser;
import me.kzheart.klib.script.kether.core.QuestContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeValueActionsTest {
    private final StatementRegistry registry = new StatementRegistry();
    private final KetherScriptEngine engine;
    NativeValueActionsTest() {
        NativeValueActions.install(registry);
        engine = new KetherScriptEngine(registry);
    }
    private Object eval(String source, ScriptContext context) { return engine.eval(source, context).toCompletableFuture().join(); }
    private Object eval(String source) { return eval(source, ScriptContext.builder().build()); }

    @Test void listMathUsesIntegerDivisionAndOnlyIntCompatibleOperands() {
        assertEquals(Integer.valueOf(1), eval("math div [ 9 5 ]"));
        assertEquals(Integer.valueOf(-1), eval("math / [ -9 5 ]"));
        assertEquals(Double.valueOf(1.8), eval("math div [ 9.0 5 ]"));
        assertEquals(Integer.valueOf(6), eval("math add [ 1 2 3 ]"));
        assertEquals(Integer.valueOf(5), eval("math - [ 10 2 3 ]"));
        assertEquals(Integer.valueOf(24), eval("math * [ 2 3 4 ]"));
        ScriptContext context = ScriptContext.builder().variable("number", Double.valueOf(2)).build();
        assertEquals(Double.valueOf(3), eval("math add [ &number 1 ]", context));
        assertEquals(Double.valueOf(2147483648d), eval("math add [ 2147483648 ]"));
    }

    @Test void emptyMathAndIntegerOverflowKeepOriginalIdentities() {
        assertEquals(Integer.valueOf(0), eval("math add [ ]"));
        assertEquals(Integer.valueOf(0), eval("math sub [ ]"));
        assertEquals(Integer.valueOf(1), eval("math mul [ ]"));
        assertEquals(Integer.valueOf(0), eval("math div [ ]"));
        assertEquals(Integer.valueOf(Integer.MIN_VALUE), eval("math add [ 2147483647 1 ]"));
    }

    @Test void infixIsLeftToRightAndPreservesTheFollowingStatement() {
        assertEquals(Integer.valueOf(9), eval("math *1 + *2 * *3"));
        assertEquals(Double.valueOf(9), eval("math 1 + 2 * 3.0"));
        assertEquals("last", eval("math 4 / 2\n*last"));
        assertThrows(CompletionException.class, () -> eval("math 1"));
    }

    @Test void numberCoercionUsesOriginalSanitisingRules() {
        assertEquals(Double.valueOf(1002), eval("math add [ '1,000' 2 ]"));
        assertEquals(Double.valueOf(9.5), eval("math add [ '(9.5, 10.6, 33.2)' ]"));
        assertEquals(Double.valueOf(0), eval("math add [ rubbish ]"));
        assertEquals(Double.valueOf(0), eval("math add [ '1-2' ]"));
        assertEquals(Integer.valueOf(0), eval("math div [ 3 0 ]"));
        assertEquals(Double.valueOf(Double.POSITIVE_INFINITY), eval("math div [ 3.0 0 ]"));
        assertEquals(Integer.valueOf(0), eval("math add [ &missing 1 ]"));
    }

    @Test void exactChestRowsExpressionKeepsIntegerMathBeforeCeil() {
        ScopeImpl scope = ScopeImpl.create("native-rows", current -> {
            // max/ceil 是 Chemdah 业务 parser，此夹具仅消费同一 ParsedAction 树。
            registry.registerKether(current, "chemdah", "ceil", QuestActionParser.of(reader -> {
                ParsedAction<?> input = reader.nextValue();
                return nativeAction(frame -> frame.newFrame(input).run().thenApply(v -> Math.ceil(Double.parseDouble(v.toString()))));
            }));
            registry.registerKether(current, "chemdah", "max", QuestActionParser.of(reader -> {
                ParsedAction<?> first = reader.nextValue(), second = reader.nextValue();
                return nativeAction(frame -> frame.newFrame(first).run().thenCompose(left ->
                        frame.newFrame(second).run().thenApply(right -> Math.max(Double.parseDouble(left.toString()), Double.parseDouble(right.toString())))));
            }));
        });
        try {
            int[] sizes = {0, 5, 6, 20}; int[] rows = {3, 3, 3, 6};
            for (int index = 0; index < sizes.length; index++) {
                ScriptContext context = ScriptContext.builder().namespaces("chemdah").variable("size", sizes[index]).build();
                assertEquals(Double.valueOf(rows[index]), eval("max ceil math add [ math div [ &size 5 ] 2 ] 3", context));
            }
        } finally { scope.close(); }
    }

    @Test void roundMatchesKotlinTieSaturationAndNaNRules() {
        assertEquals(Integer.valueOf(2), eval("round 1.5"));
        assertEquals(Integer.valueOf(-1), eval("round -1.5"));
        assertEquals(Integer.valueOf(Integer.MAX_VALUE), eval("round Infinity"));
        assertEquals(Integer.valueOf(Integer.MIN_VALUE), eval("round -Infinity"));
        assertEquals(Integer.valueOf(0), eval("round bad"));
        assertThrows(CompletionException.class, () -> eval("round NaN"));
    }

    @Test void randomIntegerRangesIncludeBothEndpointsAndAcceptReversedBounds() {
        boolean low = false, high = false;
        for (int i = 0; i < 128; i++) {
            Object value = eval("random 2 to 1"); assertInstanceOf(Integer.class, value);
            int number = ((Integer) value).intValue(); assertTrue(number == 1 || number == 2);
            low |= number == 1; high |= number == 2;
        }
        assertTrue(low && high);
        assertEquals(Integer.valueOf(5), eval("random2 5 to 5"));
        assertThrows(CompletionException.class, () -> eval("random 2147483647 to 2147483647"));
    }

    @Test void randomSingleIntExcludesUpperBoundAndDoubleRangeKeepsType() {
        for (int i = 0; i < 32; i++) {
            assertEquals(Integer.valueOf(0), eval("random 1"));
            Object value = eval("random 1.0 to -2.0"); assertInstanceOf(Double.class, value);
            double number = ((Double) value).doubleValue(); assertTrue(number >= -2 && number < 1);
        }
        assertEquals(Double.valueOf(2), eval("random 2.0 to 2.0"));
        assertEquals(Double.valueOf(0), eval("random rubbish"));
        assertThrows(CompletionException.class, () -> eval("random 0"));
        assertThrows(CompletionException.class, () -> eval("random -1"));
    }

    @Test void randomCollectionsArraysAndEmptyInputsUseElements() {
        ScriptContext context = ScriptContext.builder().variable("empty", Collections.emptyList())
                .variable("single", Collections.singleton("value")).variable("array", new Object[]{Integer.valueOf(7)})
                .variable("primitive", new int[]{7}).variable("invalid", Arrays.asList(null, "value")).build();
        assertNull(eval("random &empty", context));
        assertEquals("value", eval("random &single", context));
        assertEquals(Integer.valueOf(7), eval("random &array", context));
        assertEquals(Double.valueOf(0), eval("random &primitive", context));
        assertThrows(CompletionException.class, () -> eval("random &invalid", context));
    }

    @Test void setToConsumesNestedActionsAndRetainsTheirExactType() {
        ScriptContext context = ScriptContext.builder().build();
        assertNull(eval("set chosen to round random 1", context));
        assertEquals(Integer.valueOf(0), context.variableOrNull("chosen"));
        assertEquals(Integer.valueOf(3), eval("set rows to math add [ 1 2 ]\n&rows", context));
        assertEquals("null", eval("set text to *null\n&text", context));
        assertNull(eval("set absent to &missing\n&absent", context));
        assertNull(context.variableOrNull("absent"));
    }

    @Test void legacySetConstantSyntaxRetainsPublicInferenceAndResult() {
        ScriptContext context = ScriptContext.builder().build();
        assertEquals(new BigDecimal("12"), eval("set legacy 12", context));
        assertEquals(new BigDecimal("12"), context.variableOrNull("legacy"));
        assertEquals("value", eval("set property value\n&property", context));
        assertNull(eval("set legacy null", context));
        assertNull(context.variableOrNull("legacy"));
    }

    @Test void setPropertyEvaluatesSourceThenValueAndUsesExplicitAdapter() {
        Map<String, Object> object = new HashMap<String, Object>();
        ScriptContext context = ScriptContext.builder().variable("box", object)
                .service(ScriptPropertyAccess.class, mapProperties()).build();
        assertNull(eval("set property answer from &box to math add [ 20 22 ]", context));
        assertEquals(Integer.valueOf(42), object.get("answer"));
        assertNull(eval("set &box[label] to *result", context));
        assertEquals("result", object.get("label"));
        assertNull(eval("set &box[constant] literal", context));
        assertEquals("literal", object.get("constant"));
        assertNull(eval("set property nullable from &box to &missing", context));
        assertTrue(object.containsKey("nullable"));
        assertNull(object.get("nullable"));
    }

    @Test void propertyMissingUnsupportedOrNullFailsClearly() {
        Map<String, Object> object = new HashMap<String, Object>();
        assertThrows(CompletionException.class, () -> eval("set property name from &box to *x",
                ScriptContext.builder().variable("box", object).build()));
        assertThrows(CompletionException.class, () -> eval("set property name from &box to *x",
                ScriptContext.builder().variable("box", "unsupported").service(ScriptPropertyAccess.class, mapProperties()).build()));
        assertThrows(CompletionException.class, () -> eval("set property name from &missing to *x"));
        assertThrows(CompletionException.class, () -> eval("set '&box[name]' to *x",
                ScriptContext.builder().variable("box", object).service(ScriptPropertyAccess.class, mapProperties()).build()));
    }

    @Test void senderUsesOriginalNonPlayerConsoleRuleAndExplicitPlayerNames() {
        assertEquals("console", eval("sender"));
        assertEquals("console", eval("sender", context("console")));
        assertEquals("console", eval("sender", context("command-block")));
        assertEquals("Alex", eval("sender", context("Alex")));
        assertThrows(CompletionException.class, () -> eval("sender", ScriptContext.builder().sender(new Object()).build()));
    }

    @Test void permissionConsumesActionButOnlyAcceptsSelectedPlayer() {
        assertEquals(Boolean.TRUE, eval("permission inline 'ad{{ *min }}'", context("Alex")));
        assertEquals(Boolean.FALSE, eval("permission other", context("Alex")));
        assertThrows(CompletionException.class, () -> eval("permission admin", context("console")));
        assertThrows(CompletionException.class, () -> eval("permission admin", context("command-block")));
        assertThrows(CompletionException.class, () -> eval("permission admin"));
        assertEquals(Boolean.TRUE, eval("perm admin", context("Alex")));
    }

    @Test @Timeout(5) void asyncMathWaitsSequentiallyAndResumesOnFrameExecutor() throws Exception {
        CompletableFuture<Object> first = new CompletableFuture<Object>();
        List<String> calls = new ArrayList<String>();
        ScopeImpl scope = ScopeImpl.create("native-sequential", current -> {
            registry.registerKether(current, "klib", "first", QuestActionParser.of(reader -> nativeAction(frame -> {
                calls.add("first"); return first;
            })));
            registry.registerKether(current, "klib", "second", QuestActionParser.of(reader -> nativeAction(frame -> {
                calls.add(Thread.currentThread().getName()); return CompletableFuture.completedFuture(Integer.valueOf(2));
            })));
        });
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "native-main"));
        try {
            KetherScriptEngine async = new KetherScriptEngine(registry, null, executor);
            CompletableFuture<Object> result = async.eval("math add [ first second ]", ScriptContext.builder().build()).toCompletableFuture();
            assertFalse(result.isDone()); assertEquals(Collections.singletonList("first"), calls);
            first.complete(Integer.valueOf(1));
            assertEquals(Integer.valueOf(3), result.get(2, TimeUnit.SECONDS));
            assertEquals(Arrays.asList("first", "native-main"), calls);
        } finally { scope.close(); executor.shutdownNow(); }
    }

    @Test void missingRequiredValuesCannotConsumeClosingDelimiters() {
        for (String source : Arrays.asList("round", "random", "permission", "set", "set x", "set x to",
                "set x to round", "random 1 to", "math add [ 1", "math add [ round ]")) {
            assertThrows(CompletionException.class, () -> eval(source), source);
        }
        assertEquals("last", eval("math add [ 1 ]\n*last"));
    }

    @Test @Timeout(2) void infixFirstValueFailureTerminatesInsteadOfLeavingAnUnfinishedFuture() {
        assertThrows(CompletionException.class, () -> eval("math rubbish + 1"));
        assertThrows(CompletionException.class, () -> eval("math &missing + 1"));
    }

    private ScriptContext context(String name) {
        return ScriptContext.builder().sender(name).service(ScriptSenderQuery.class, new ScriptSenderQuery() {
            @Override public boolean isPlayer(Object sender) { return "Alex".equals(sender); }
            @Override public String name(Object sender) { return String.valueOf(sender); }
        }).service(PlayerQuery.class, new PlayerQuery() {
            @Override public Object property(Object sender, String key) { return null; }
            @Override public boolean hasPermission(Object sender, String permission) { return "admin".equals(permission); }
        }).build();
    }
    private ScriptPropertyAccess mapProperties() {
        return new ScriptPropertyAccess() {
            @Override public Result read(Object instance, String key) {
                return instance instanceof Map<?, ?> ? Result.supported(((Map<?, ?>) instance).get(key)) : Result.unsupported();
            }
            @Override @SuppressWarnings("unchecked") public Result write(Object instance, String key, Object value) {
                if (!(instance instanceof Map<?, ?>)) return Result.unsupported();
                ((Map<String, Object>) instance).put(key, value); return Result.supported(null);
            }
        };
    }
    private static QuestAction<Object> nativeAction(Function<QuestContext.Frame, CompletableFuture<Object>> operation) {
        return new QuestAction<Object>() {
            @Override public CompletableFuture<Object> process(QuestContext.Frame frame) { return operation.apply(frame); }
        };
    }
}
