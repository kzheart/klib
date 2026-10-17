package me.kzheart.klib.script;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import me.kzheart.klib.scope.ScopeImpl;
import me.kzheart.klib.script.kether.core.ParsedAction;
import me.kzheart.klib.script.kether.core.QuestAction;
import me.kzheart.klib.script.kether.core.QuestActionParser;
import me.kzheart.klib.script.kether.core.QuestContext;
import me.kzheart.klib.script.kether.core.QuestFuture;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class ScriptFramesTest {
    private final StatementRegistry registry = new StatementRegistry();
    private final Object sender = new Object();
    private final List<String> messages = new ArrayList<String>();
    private final ScriptContext context = ScriptContext.builder().sender(sender)
            .variable("value", "initial")
            .service(MessageSink.class, (target, message) -> {
                assertSame(sender, target); messages.add(message);
            }).build();

    @Test void nativeWritesAreImmediatelyVisibleToNormalAndStructuredActionsAndCaller() {
        ScopeImpl scope = ScopeImpl.create("native-write", ignored -> { });
        try {
            nativeAction(scope, "nativewrite", frame -> {
                frame.variables().set("value", "native");
                return CompletableFuture.completedFuture(null);
            });
            KetherScriptEngine engine = new KetherScriptEngine(registry);
            assertEquals("native", engine.eval("nativewrite; tell inline *\"{{ &value }}\"; get value", context)
                    .toCompletableFuture().join());
            assertEquals(Arrays.asList("native"), messages);
            assertEquals("native", context.variableOrNull("value"));
        } finally { scope.close(); }
    }

    @Test void hostWritesAndServicesAreVisibleInTheSameNativeFrame() {
        ScopeImpl scope = ScopeImpl.create("host-write", ignored -> { });
        try {
            nativeAction(scope, "bridge", frame -> {
                ScriptContext host = ScriptFrames.context(frame);
                assertSame(sender, host.sender().get());
                assertSame(context.requireService(MessageSink.class), host.requireService(MessageSink.class));
                host.setVariable("value", "host");
                assertEquals("host", frame.variables().getOrNull("value"));
                return CompletableFuture.completedFuture(host.variableOrNull("value"));
            });
            assertEquals("host", new KetherScriptEngine(registry).eval("bridge", context).toCompletableFuture().join());
            assertEquals("host", context.variableOrNull("value"));
        } finally { scope.close(); }
    }

    @Test void nestedEvalWritesBackNativeChangesAndRemovalsBeforeFollowingActions() {
        ScopeImpl scope = ScopeImpl.create("nested", ignored -> { });
        try {
            nativeAction(scope, "write", frame -> {
                frame.variables().set("added", "nested");
                frame.context().rootFrame().variables().remove("value");
                return CompletableFuture.completedFuture("ok");
            });
            nativeAction(scope, "nested", frame -> ScriptFrames.eval(frame, "write").toCompletableFuture());
            assertEquals("nested", new KetherScriptEngine(registry).eval("nested; get added", context)
                    .toCompletableFuture().join());
            assertFalse(context.variables().containsKey("value"));
            assertEquals("nested", context.variableOrNull("added"));
        } finally { scope.close(); }
    }

    @Test void normalStatementNestedEvaluationSharesTheLiveFrameView() {
        ScopeImpl scope = ScopeImpl.create("normal-nested", ignored -> { });
        try {
            nativeAction(scope, "write", frame -> {
                frame.variables().set("value", "nested-native");
                return CompletableFuture.completedFuture(null);
            });
            registry.register(scope, "klib", "normal", (call, host) -> call.eval("write", host));
            assertEquals("nested-native", new KetherScriptEngine(registry).eval("normal; get value", context)
                    .toCompletableFuture().join());
        } finally { scope.close(); }
    }

    @Test void localVariablesStayInTheirFrameAndNearestParentWins() {
        ScopeImpl scope = ScopeImpl.create("locals", ignored -> { });
        try {
            nativeAction(scope, "locals", frame -> {
                frame.variables().set("~local", "root");
                QuestContext.Frame child = frame.newFrame(new ParsedAction<Object>(new QuestAction<Object>() {
                    @Override public CompletableFuture<Object> process(QuestContext.Frame current) {
                        ScriptContext host = ScriptFrames.context(current);
                        assertEquals("root", host.variableOrNull("~local"));
                        host.setVariable("~local", "child");
                        assertEquals("child", host.variables().get("~local"));
                        assertEquals("root", ScriptFrames.context(frame).variableOrNull("~local"));
                        host.removeVariable("~local");
                        assertEquals("root", host.variableOrNull("~local"));
                        return CompletableFuture.completedFuture(null);
                    }
                }));
                return child.run();
            });
            new KetherScriptEngine(registry).eval("locals", context).toCompletableFuture().join();
            assertEquals("root", context.variableOrNull("~local"));
        } finally { scope.close(); }
    }

    @Test void internalKeysCannotLeakOrBeOverwrittenThroughThePublicHostView() {
        ScopeImpl scope = ScopeImpl.create("internal", ignored -> { });
        try {
            nativeAction(scope, "inspect", frame -> {
                ScriptContext host = ScriptFrames.context(frame);
                assertNull(host.variableOrNull("~klib:context"));
                assertTrue(host.variables().keySet().stream().noneMatch(key -> key.startsWith("~klib:")));
                assertThrows(IllegalArgumentException.class, () -> host.setVariable("~klib:context", "bad"));
                assertThrows(IllegalArgumentException.class, () -> host.removeVariable("~klib:context"));
                return CompletableFuture.completedFuture(null);
            });
            new KetherScriptEngine(registry).eval("inspect", context).toCompletableFuture().join();
            assertTrue(context.variables().keySet().stream().noneMatch(key -> key.startsWith("~klib:")));
        } finally { scope.close(); }
    }

    @Test void nullSnapshotsSurviveNativeRoundTripsWithoutChangingSetNullRemovalContract() {
        ScriptContext nullable = ScriptContext.builder().variable("nil", null).build();
        ScopeImpl scope = ScopeImpl.create("null", ignored -> { });
        try {
            nativeAction(scope, "nulls", frame -> {
                Map<String, Object> snapshot = ScriptFrames.variables(frame);
                assertTrue(snapshot.containsKey("nil")); assertNull(snapshot.get("nil"));
                assertThrows(UnsupportedOperationException.class, () -> snapshot.put("x", 1));
                frame.variables().set("another", null);
                ScriptFrames.context(frame).setVariable("nil", null);
                return CompletableFuture.completedFuture(null);
            });
            new KetherScriptEngine(registry).eval("nulls", nullable).toCompletableFuture().join();
            assertFalse(nullable.variables().containsKey("nil"));
            assertTrue(nullable.variables().containsKey("another"));
            assertNull(nullable.variableOrNull("another"));
        } finally { scope.close(); }
    }

    @Test void nativeWritesBeforeFailureAreRetainedAndUnchangedHostWritesAreNotLost() {
        ScopeImpl scope = ScopeImpl.create("failure", ignored -> { });
        try {
            nativeAction(scope, "fail", frame -> {
                frame.variables().set("value", "before-failure");
                context.setVariable("external", "concurrent");
                CompletableFuture<Object> failed = new CompletableFuture<Object>();
                failed.completeExceptionally(new IllegalStateException("expected"));
                return failed;
            });
            assertThrows(CompletionException.class, () -> new KetherScriptEngine(registry)
                    .eval("fail", context).toCompletableFuture().join());
            assertEquals("before-failure", context.variableOrNull("value"));
            assertEquals("concurrent", context.variableOrNull("external"));
        } finally { scope.close(); }
    }

    @Test void removingAnUnreadyDeferredVariableDoesNotReadOrAwaitIt() {
        CompletableFuture<Object> pending = new CompletableFuture<Object>();
        ScopeImpl scope = ScopeImpl.create("deferred-remove", ignored -> { });
        try {
            nativeAction(scope, "removefuture", frame -> {
                ParsedAction<Object> owner = new ParsedAction<Object>(new QuestAction<Object>() {
                    @Override public CompletableFuture<Object> process(QuestContext.Frame ignored) { return pending; }
                });
                frame.variables().set("pending", owner, pending);
                assertNotNull(ScriptFrames.context(frame).removeVariable("pending"));
                assertFalse(frame.variables().keys().contains("pending"));
                assertFalse(pending.isDone());
                return CompletableFuture.completedFuture(null);
            });
            new KetherScriptEngine(registry).eval("removefuture", context).toCompletableFuture().join();
            assertFalse(context.variables().containsKey("pending"));
        } finally { scope.close(); }
    }

    @Test void completedDeferredVariablesAreBorrowedByNestedAndRepeatedEvaluations() {
        AtomicInteger calls = new AtomicInteger();
        ParsedAction<Object> owner = new ParsedAction<Object>(new QuestAction<Object>() {
            @Override public CompletableFuture<Object> process(QuestContext.Frame frame) {
                calls.incrementAndGet(); return CompletableFuture.completedFuture("ready");
            }
        });
        QuestFuture<Object> declared = new QuestFuture<Object>(owner);
        ScriptContext host = ScriptContext.builder().variable("deferred", declared).build();
        ScopeImpl scope = ScopeImpl.create("borrowed-ready", ignored -> { });
        try {
            nativeAction(scope, "nestedread", frame -> ScriptFrames.eval(frame, "get deferred").toCompletableFuture());
            KetherScriptEngine engine = new KetherScriptEngine(registry);
            assertEquals("ready", engine.eval("nestedread", host).toCompletableFuture().join());
            assertEquals("ready", engine.eval("nestedread", host).toCompletableFuture().join());
            assertEquals(1, calls.get());
            assertSame(declared, host.variables().get("deferred"));
            assertNotNull(declared.getFuture());
        } finally { scope.close(); }
    }

    @Test void pendingDeferredBorrowKeepsNonblockingReadsAndDoesNotCloseItsOwner() {
        CompletableFuture<Object> pending = new CompletableFuture<Object>();
        ParsedAction<Object> owner = new ParsedAction<Object>(new QuestAction<Object>() {
            @Override public CompletableFuture<Object> process(QuestContext.Frame frame) {
                fail("already started borrowed action must not run again"); return pending;
            }
        });
        QuestFuture<Object> running = new QuestFuture<Object>(owner, pending);
        ScriptContext host = ScriptContext.builder().variable("deferred", running).build();
        ScopeImpl scope = ScopeImpl.create("borrowed-pending", ignored -> { });
        try {
            nativeAction(scope, "inspectborrow", frame -> {
                QuestFuture<?> borrowed = frame.variables().getFuture("deferred").get();
                assertNotSame(running, borrowed);
                assertSame(pending, borrowed.getFuture());
                assertThrows(IllegalStateException.class, () -> ScriptFrames.context(frame).variable("deferred"));
                assertFalse(pending.isDone());
                frame.variables().close();
                assertNull(borrowed.getFuture());
                assertSame(pending, running.getFuture());
                return CompletableFuture.completedFuture("ok");
            });
            nativeAction(scope, "nestedborrow", frame -> ScriptFrames.eval(frame, "inspectborrow").toCompletableFuture());
            KetherScriptEngine engine = new KetherScriptEngine(registry);
            assertEquals("ok", engine.eval("nestedborrow", host).toCompletableFuture().join());
            assertSame(running, host.variables().get("deferred"));
            pending.complete("finished");
            assertEquals("finished", engine.eval("get deferred", host).toCompletableFuture().join());
        } finally { scope.close(); }
    }

    @Test void asyncNativeChangesReachTheNextHostActionOnTheContinuationExecutor() {
        ArrayDeque<Runnable> queue = new ArrayDeque<Runnable>();
        CompletableFuture<Object> gate = new CompletableFuture<Object>();
        ScopeImpl scope = ScopeImpl.create("async", ignored -> { });
        try {
            nativeAction(scope, "waitnative", frame -> gate.thenApply(value -> {
                frame.variables().set("value", value); return value;
            }));
            CompletableFuture<Object> result = new KetherScriptEngine(registry, null, queue::add)
                    .eval("waitnative; tell inline *\"{{ &value }}\"", context).toCompletableFuture();
            gate.complete("after"); assertFalse(result.isDone()); assertTrue(messages.isEmpty());
            while (!queue.isEmpty()) queue.remove().run();
            assertEquals("after", result.join());
            assertEquals(Arrays.asList("after"), messages);
            assertEquals("after", context.variableOrNull("value"));
        } finally { scope.close(); }
    }

    private void nativeAction(ScopeImpl scope, String name,
            Function<QuestContext.Frame, CompletableFuture<Object>> action) {
        registry.registerKether(scope, "klib", name, QuestActionParser.of(reader -> new QuestAction<Object>() {
            @Override public CompletableFuture<Object> process(QuestContext.Frame frame) { return action.apply(frame); }
        }));
    }
}
