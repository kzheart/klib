package me.kzheart.klib.script;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import me.kzheart.klib.scope.ScopeImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StructuredScriptActionsTest {
    private final StatementRegistry registry = new StatementRegistry();
    private final KetherScriptEngine engine = new KetherScriptEngine(registry);

    @ParameterizedTest
    @CsvSource({"0,7", "15,37", "16,42", "30,112", "31,121", "100,742"})
    void experienceBranchesUseRealJexlAndInclusiveBoundaries(int level, int expected) {
        Object actual = eval("case &level [\n"
                + "when <= 15 -> calc \"level * 2 + 7\"\n"
                + "when <= 30 -> calc \"level * 5 - 38\"\n"
                + "else calc \"level * 9 - 158\"\n]", context(level));
        assertEquals(expected, ((Number) actual).intValue());
    }

    @Test
    void nestedTextPreservesQuotedLiteralsAndExpandsScriptExpressions() {
        List<String> messages = new ArrayList<String>();
        ScriptContext context = ScriptContext.builder().variable("level", 5)
                .service(MessageSink.class, (sender, message) -> messages.add(message)).build();
        assertEquals("§aLevel up! §f5!", eval("tell colored inline *\"&aLevel up! &f{{ &level }}!\"", context));
        assertEquals("§3[Chemdah] §7已接受任务 §3[开采矿石]",
                eval("tell colored \"&3[Chemdah] &7已接受任务 &3[开采矿石]\"", context));
        assertEquals("calc", eval("tell 'calc'", context));
        assertEquals("&literal", eval("tell '&literal'", context));
        assertEquals("6 / 5", eval("inline '{{ calc \"level + 1\" }} / ${level}'", context));
        assertEquals("§x§a§b§1§2§c§dhex", eval("color '&#Ab12Cdhex'", context));
        assertEquals(4, messages.size());
    }

    @Test
    void caseEvaluatesInputOnceAndOnlyRunsSelectedBody() {
        List<String> called = new ArrayList<String>();
        ScopeImpl scope = ScopeImpl.create("case-input", current -> registry.register(current, "input",
                (call, context) -> { called.add("input"); return CompletableFuture.completedFuture(20); }));
        try {
            ScriptContext context = ScriptContext.builder()
                    .service(MessageSink.class, (sender, message) -> called.add(message)).build();
            assertEquals("chosen", eval("case { input } [ when < 10 -> tell wrong when <= 20 -> tell chosen else tell wrong ]", context));
            assertEquals(Arrays.asList("input", "chosen"), called);
            assertNull(eval("case 3 [ when 4 then *missing ]", context));
        } finally { scope.close(); }
    }

    @Test
    void caseSupportsListsInferredEqualityAndStrictComparisons() {
        ScriptContext context = ScriptContext.builder().variable("name", "Alex")
                .variable("items", Arrays.asList("Alex", "Steve")).variable("number", 3).build();
        assertEquals("yes", eval("case &name [ when [ Steve Alex ] -> *yes else *no ]", context));
        assertEquals("yes", eval("case &name [ when in &items -> *yes else *no ]", context));
        assertEquals("yes", eval("case &name [ when in [ Steve Alex ] -> *yes else *no ]", context));
        assertEquals("yes", eval("case &items [ when has Alex -> *yes else *no ]", context));
        assertEquals("yes", eval("case 3.0 [ when 3 -> *yes else *no ]", context));
        assertEquals("no", eval("case &number [ when =! 3 -> *yes else *no ]", context));
        assertEquals("yes", eval("case &name [ when =!! &name -> *yes else *no ]", context));
        assertEquals("yes", eval("case ALEX [ when =? Alex -> *yes else *no ]", context));
        assertEquals("yes", eval("case false [ when != rubbish -> *yes else *no ]", context));
    }

    @Test
    void jexlSupportsDynamicExpressionsCollectionsAndScripts() {
        ScriptContext context = context(4);
        assertEquals(16, ((Number) eval("calc dynamic inline '{{ &level }} * level'", context)).intValue());
        assertEquals(8, ((Number) eval("invoke 'var doubled = level * 2; return doubled;'", context)).intValue());
        assertEquals(3, ((Number) eval("calc 'size([1, 2, 3])'", context)).intValue());
    }

    @Test
    void nestedInlineWritesAreVisibleToFollowingFrameActions() {
        ScriptContext context = context(4);
        assertEquals("updated", eval("inline '{{ set status updated }}'\n&status", context));
        assertEquals("updated", context.variableOrNull("status"));
    }

    @Test
    void standaloneUnknownActionsAndBrokenNestedActionsStillFail() {
        ScriptContext context = context(4);
        assertThrows(CompletionException.class, () -> eval("unregistered", context));
        assertThrows(CompletionException.class, () -> eval("tell colored { unregistered }", context));
        assertThrows(CompletionException.class, () -> eval("calc dynamic { unregistered }", context));
        assertThrows(CompletionException.class, () -> eval("case 1 [ when 1 ??? *bad ]", context));
        assertThrows(CompletionException.class, () -> eval("calc 'missingVariable + 1'", context));
    }

    @Test
    void ifWithoutElseLeavesTheNextStatementUnread() {
        assertEquals("last", eval("if { eq 1 1 } then { literal first }\nliteral last", context(0)));
    }

    @Test
    void builtinOverridesAndRestorationInvalidateTheCompiledTree() {
        ScriptContext context = context(4);
        assertEquals("§agreen", eval("colored '&agreen'", context));
        ScopeImpl scope = ScopeImpl.create("override-colored", current -> registry.register(current,
                "klib", "colored", (call, ignored) -> CompletableFuture.completedFuture("custom")));
        try {
            assertEquals("custom", eval("colored '&agreen'", context));
        } finally { scope.close(); }
        assertEquals("§agreen", eval("colored '&agreen'", context));
    }

    @Test
    @Timeout(5)
    void nestedAsyncValuesResumeOnTheHostExecutor() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "structured-main"));
        try {
            CompletableFuture<Object> pending = new CompletableFuture<Object>();
            AtomicReference<String> thread = new AtomicReference<String>();
            ScriptContext context = ScriptContext.builder().service(DelayScheduler.class, duration -> pending)
                    .service(MessageSink.class, (sender, message) -> thread.set(Thread.currentThread().getName())).build();
            KetherScriptEngine asyncEngine = new KetherScriptEngine(registry, null, executor);
            CompletableFuture<Object> result = asyncEngine.eval(
                    "tell colored case delay 1ms [ when 5 -> inline 'level {{ calc \"2 + 3\" }}' else *wrong ]",
                    context).toCompletableFuture();
            Thread worker = new Thread(() -> pending.complete(5), "database-worker");
            worker.start();
            worker.join();
            assertEquals("level 5", result.get(2, TimeUnit.SECONDS));
            assertEquals("structured-main", thread.get());
        } finally {
            executor.shutdownNow();
        }
    }

    private ScriptContext context(int level) { return ScriptContext.builder().variable("level", level).build(); }
    private Object eval(String source, ScriptContext context) { return engine.eval(source, context).toCompletableFuture().join(); }
}
