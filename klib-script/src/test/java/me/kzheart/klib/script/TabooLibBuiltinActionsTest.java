package me.kzheart.klib.script;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.regex.Matcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 原框架内置语句（流程、集合、文本、时间、属性）的行为对照。 */
class TabooLibBuiltinActionsTest {
    private final KetherScriptEngine engine = new KetherScriptEngine(new StatementRegistry(), null, Runnable::run, true);
    private final List<String> logs = new ArrayList<String>();
    private final List<String> messages = new ArrayList<String>();
    private final List<CompletableFuture<Object>> delays = new ArrayList<CompletableFuture<Object>>();

    private ScriptContext.Builder context() {
        return ScriptContext.builder()
                .sender("Alex")
                .service(ScriptLogger.class, (level, message) -> logs.add(level.getName() + ":" + message))
                .service(MessageSink.class, (sender, message) -> messages.add(sender + ">" + message))
                .service(ScriptSenderQuery.class, new ScriptSenderQuery() {
                    @Override public boolean isPlayer(Object target) { return true; }
                    @Override public String name(Object target) { return String.valueOf(target); }
                })
                .service(DelayScheduler.class, duration -> {
                    CompletableFuture<Object> future = new CompletableFuture<Object>();
                    delays.add(future);
                    return future;
                });
    }

    private Object eval(String source) { return eval(source, context().build()); }
    private Object eval(String source, ScriptContext context) { return engine.eval(source, context).toCompletableFuture().join(); }

    @Test void loopsBindElementsAndStopOnBreak() {
        assertEquals("6", String.valueOf(eval("set sum to 0\nfor i in array [ 1 2 3 ] then { set sum to math add [ &sum &i ] }\n&sum")));
        assertEquals(Arrays.asList(2, 4), eval("map i in array [ 1 2 3 ] with { if check &i == 3 then break else math mul [ &i 2 ] }"));
        assertEquals(Integer.valueOf(3), eval("set n to 0\nwhile check &n < 10 then { set n to math add [ &n 1 ] if check &n == 3 then break }\n&n"));
        Map<String, Object> map = new HashMap<String, Object>();
        map.put("k", "v");
        assertEquals("k=v", eval("set out to pass\nfor e in &map then { set out to join [ &e-key &e-value ] by = }\n&out",
                context().variable("map", map).build()));
        assertEquals(Integer.valueOf(9), eval("set n to 0\nrepeat 9 { set n to math add [ &n 1 ] }\n&n"));
    }

    @Test void longSynchronousLoopsDoNotExhaustTheStack() {
        assertEquals(Integer.valueOf(5000), eval("set n to 0\nfor i in range 1 to 5000 then { set n to &i }\n&n"));
    }

    @Test void exitStopsTheScriptAndCompletesNormally() {
        assertNull(eval("tell before\nexit\ntell after"));
        assertEquals(Arrays.asList("Alex>before"), messages);
        assertNull(eval("if true then { stop }\ntell never"));
        assertEquals(1, messages.size());
    }

    @Test void waitSuspendsUntilTheHostSchedulerCompletes() {
        CompletableFuture<Object> result = engine.eval("wait 1s\ntell done", context().build()).toCompletableFuture();
        assertFalse(result.isDone());
        assertEquals(1, delays.size());
        delays.get(0).complete(null);
        assertTrue(result.isDone());
        assertEquals(Arrays.asList("Alex>done"), messages);
    }

    @Test void blocksCallAndGoto() {
        assertEquals("sub", eval("def main = { call sub }\ndef sub = { pass sub }".replace("pass sub", "literal sub")));
        assertEquals("jumped", eval("def main = { goto other\ntell skipped }\ndef other = { tell jumped }"));
        assertEquals(Arrays.asList("Alex>jumped"), messages);
    }

    @Test void sequenceAsyncAndAwait() {
        assertEquals("c", eval("seq [ literal a literal b literal c ]"));
        assertEquals("x", eval("set f to async literal x\nawait &f"));
        assertNull(eval("await_all [ literal a literal b ]"));
        assertEquals("fallback", eval("optional null else literal fallback"));
        assertEquals("value", eval("optional literal value else literal fallback"));
        assertEquals("", eval("pass"));
    }

    @Test void logStatementsUseTheHostLogger() {
        eval("log hello\nwarn careful\nerror broken");
        assertEquals(Arrays.asList(Level.INFO.getName() + ":hello", Level.WARNING.getName() + ":careful", Level.SEVERE.getName() + ":broken"), logs);
    }

    @Test void tellAliasesReplaceSenderPlaceholder() {
        eval("send \"hi @sender\"\nmessage plain");
        assertEquals(Arrays.asList("Alex>hi Alex", "Alex>plain"), messages);
    }

    @Test void arraysAreMutableAndIndexed() {
        // 与原框架一致：字面量词元是字符串。
        assertEquals(Arrays.asList("0", "1", "2", "3"), eval("set a to array [ 1 2 ]\narr-add 3 to &a\narr-push 0 to &a\n&a"));
        assertEquals(Integer.valueOf(2), eval("set a to array [ a b c ]\narr-find c in &a"));
        assertEquals("b", eval("set a to array [ a b c ]\nelem 1 of &a"));
        assertNull(eval("set a to array [ a ]\narr-get 5 in &a"));
        assertEquals(Arrays.asList("b"), eval("set a to array [ a b c ]\narr-take &a\narr-drop &a\n&a"));
        assertEquals("b", eval("set a to array [ a b ]\narr-remove-at 1 in &a"));
        assertEquals(Integer.valueOf(3), eval("size array [ 1 2 3 ]"));
        assertEquals(Integer.valueOf(5), eval("length hello"));
        assertEquals(Arrays.asList("3", "2", "1"), eval("reverse array [ 1 2 3 ]"));
        assertEquals(3, ((List<?>) eval("shuffle array [ 1 2 3 ]")).size());
    }

    @Test void splitJoinAndRange() {
        assertEquals(Arrays.asList("a", "b", ""), eval("split \"a,b,\" by \",\""));
        assertEquals(Arrays.asList("a", "b"), eval("split ab"));
        assertEquals("1-2-3", eval("join [ 1 2 3 ] by -"));
        assertEquals("123", eval("join [ 1 2 3 ]"));
        assertEquals(Arrays.asList(1, 2, 3), eval("range 1 to 3"));
        assertEquals(Arrays.asList(0, 2, 4), eval("range 0 to 5 step 2"));
        assertEquals(Arrays.asList(0.0, 0.5, 1.0), eval("range 0 to 1 step 0.5"));
    }

    @Test void klibJoinFormStillWorks() {
        assertEquals("a,b", eval("join , a b"));
    }

    @Test void textFormatting() {
        assertEquals("Hello", eval("uncolored \"&aHel&#ff0000lo\""));
        assertEquals(Double.valueOf(3.14), eval("scale 3.14159"));
        assertEquals(Arrays.asList("a", "ab_", "abc"), eval("printed abc"));
        assertEquals(Arrays.asList("a", "ab_", "abc", "abcd_", "abcd"), eval("printed abcd"));
        assertEquals("1970/01/01", ((String) eval("format 0 by yyyy/MM/dd")).substring(0, 10).replace("1969/12/31", "1970/01/01"));
        Matcher matcher = (Matcher) eval("match \"Level 12\" by \"level ([0-9]+)\"");
        assertEquals("12", matcher.group(1));
    }

    @Test void clockStatementsReturnNumbers() {
        assertInstanceOf(Long.class, eval("time"));
        assertInstanceOf(String.class, eval("date as yyyy"));
        assertInstanceOf(Long.class, eval("day of week"));
        assertInstanceOf(Long.class, eval("year"));
        assertInstanceOf(Long.class, eval("minutes"));
    }

    @Test void propertyShorthandReadsBuiltinTypes() {
        Map<String, Object> map = new HashMap<String, Object>();
        map.put("gold", 7);
        ScriptContext context = context().variable("text", "Abc").variable("map", map)
                .variable("list", new ArrayList<Object>(Arrays.asList("x", "y"))).build();
        assertEquals("ABC", eval("&text[upper]", context));
        assertEquals(Integer.valueOf(3), eval("&text[length]", context));
        assertEquals(Integer.valueOf(7), eval("&map[@gold]", context));
        assertEquals("y", eval("&list[1]", context));
        assertEquals(Integer.valueOf(2), eval("get property size from &list", context));
        assertEquals("12", eval("set m to match \"Level 12\" by \"([0-9]+)\"\n&m[1]", context));
        eval("set &map[@silver] to 3\nset &list[0] to z", context);
        assertEquals("3", map.get("silver"));
    }

    @Test void variablesListsVisibleKeys() {
        @SuppressWarnings("unchecked") List<Object> keys = (List<Object>) eval("set a to 1\nvars", context().variable("b", 2).build());
        assertTrue(keys.contains("a") && keys.contains("b"));
    }
}
