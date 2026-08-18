package me.kzheart.klib.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import me.kzheart.klib.scope.ScopeImpl;
import me.kzheart.klib.script.TabooLibKetherInterop.OpenContainer;
import me.kzheart.klib.script.TabooLibKetherInterop.OpenResult;
import me.kzheart.klib.script.kether.core.ParsedAction;
import me.kzheart.klib.script.kether.core.ExitStatus;
import me.kzheart.klib.script.kether.core.SimpleQuestContext;
import me.kzheart.klib.script.kether.core.SimpleQuestService;
import me.kzheart.klib.script.kether.core.QuestAction;
import me.kzheart.klib.script.kether.core.QuestActionParser;
import me.kzheart.klib.script.kether.core.QuestContext;
import me.kzheart.klib.script.kether.core.QuestReader;
import org.junit.jupiter.api.Test;

class TabooLibKetherInteropTest {

    @Test
    void onlySharedActionsArePublishedAndLateContainersAreReplayed() {
        ScopeImpl scope = new ScopeImpl("test");
        StatementRegistry registry = new StatementRegistry();
        FakeDiscovery discovery = new FakeDiscovery();
        FakeContainer first = new FakeContainer("First");
        discovery.containers.add(first);
        TabooLibKetherInterop interop = TabooLibKetherInterop.install(
                scope, registry, "KlibProvider", discovery);

        registry.registerKether(scope, "plugin", "local", QuestActionParser.of(
                reader -> KetherRegistrationTest.literal("local")));
        StatementRegistration shared = registry.registerShared(
                scope, "plugin", "shared", QuestActionParser.of(
                        reader -> KetherRegistrationTest.literal("shared")));

        assertFalse(first.channelsFor("local").contains(TabooLibKetherInterop.ADD_ACTION));
        assertEquals(1, first.channelsFor("shared").size());

        FakeContainer late = new FakeContainer("Late");
        discovery.containers.add(late);
        interop.refresh();
        assertEquals(Collections.singletonList(TabooLibKetherInterop.ADD_ACTION),
                late.channelsFor("shared"));

        shared.dispose();
        assertTrue(first.channelsFor("shared").contains(TabooLibKetherInterop.REMOVE_ACTION));
        scope.close();
    }

    @Test
    void nestedUnknownActionsResolveDuringParsingWithTheRealReader() {
        ScopeImpl scope = new ScopeImpl("test");
        StatementRegistry registry = new StatementRegistry();
        FakeDiscovery discovery = new FakeDiscovery();
        FakeContainer remote = new FakeContainer("Remote");
        discovery.containers.add(remote);
        TabooLibKetherInterop interop = TabooLibKetherInterop.install(
                scope, registry, "KlibProvider", discovery);
        remote.resolveActions = true;

        Object value = new KetherScriptEngine(registry, interop).eval(
                "outer inner nested-value",
                ScriptContext.builder().build()).toCompletableFuture().join();

        assertEquals("nested-value", value);
        assertEquals(Arrays.asList("outer", "inner"), remote.resolved);
        scope.close();
    }

    @Test
    void importedActionsAreLocalOnlyAndRemovedByProtocol() {
        ScopeImpl scope = new ScopeImpl("test");
        StatementRegistry registry = new StatementRegistry();
        FakeDiscovery discovery = new FakeDiscovery();
        FakeContainer remote = new FakeContainer("Remote");
        discovery.containers.add(remote);
        TabooLibKetherInterop interop = TabooLibKetherInterop.install(
                scope, registry, "KlibProvider", discovery);
        remote.resolveActions = true;

        assertTrue(TabooLibKetherInterop.call(
                TabooLibKetherInterop.ADD_ACTION,
                new Object[] {"Remote", new String[] {"inner"}, "remote"})
                .isSuccessful());

        Object value = new KetherScriptEngine(registry).eval(
                "inner imported",
                ScriptContext.builder().namespaces("remote").build())
                .toCompletableFuture().join();
        assertEquals("imported", value);
        assertTrue(remote.channelsFor("inner").isEmpty());

        assertTrue(TabooLibKetherInterop.call(
                TabooLibKetherInterop.REMOVE_ACTION,
                new Object[] {new String[] {"inner"}, "remote"})
                .isSuccessful());
        scope.close();
    }

    @Test
    void remoteActionFutureCompletesWithoutFlatteningExecution() {
        ScopeImpl scope = new ScopeImpl("test");
        StatementRegistry registry = new StatementRegistry();
        FakeDiscovery discovery = new FakeDiscovery();
        FakeContainer remote = new FakeContainer("Remote");
        remote.resolveActions = true;
        discovery.containers.add(remote);
        TabooLibKetherInterop interop = TabooLibKetherInterop.install(
                scope, registry, "KlibProvider", discovery);

        CompletableFuture<Object> result = new KetherScriptEngine(
                registry, interop, Runnable::run).eval(
                        "async delayed",
                        ScriptContext.builder().build()).toCompletableFuture();
        assertFalse(result.isDone());
        remote.pending.complete("delayed");
        assertEquals("delayed", result.join());
        scope.close();
    }

    @Test
    void sharedParserIsResolvedThroughTheOpenApiEndpoint() {
        ScopeImpl scope = new ScopeImpl("test");
        StatementRegistry registry = new StatementRegistry();
        FakeDiscovery discovery = new FakeDiscovery();
        FakeContainer remote = new FakeContainer("Remote");
        discovery.containers.add(remote);
        TabooLibKetherInterop.install(scope, registry, "KlibProvider", discovery);
        registry.registerShared(scope, "plugin", "echo", QuestActionParser.of(reader -> {
            final String token = reader.nextToken();
            return KetherRegistrationTest.literal(token);
        }));

        me.kzheart.klib.script.taboolib.common.OpenResult endpoint =
                me.kzheart.klib.script.taboolib.common.OpenAPI.call(
                        TabooLibKetherInterop.REMOTE_RESOLVE,
                        new Object[] {
                            "Remote", new OneTokenReader("endpoint"), "echo", "plugin"
                        });

        assertTrue(endpoint.isSuccessful());
        assertTrue(endpoint.getValue() instanceof QuestAction);
        scope.close();
    }

    @Test
    void derivesOpenApiNameFromTheRelocatedTabooLibGroup() {
        assertEquals("com.example.shadow.taboolib.common.OpenAPI",
                ReflectiveTabooLibContainers.apiName(
                        "com.example.shadow.taboolib.platform.BukkitPlugin"));
    }

    @Test
    void replacingAContainerInvalidatesImportedParsersAndCompiledQuests() {
        ScopeImpl scope = new ScopeImpl("test");
        StatementRegistry registry = new StatementRegistry();
        FakeDiscovery discovery = new FakeDiscovery();
        FakeContainer old = new FakeContainer("Remote");
        old.resolveActions = true;
        discovery.containers.add(old);
        TabooLibKetherInterop interop = TabooLibKetherInterop.install(
                scope, registry, "KlibProvider", discovery);
        TabooLibKetherInterop.call(
                TabooLibKetherInterop.ADD_ACTION,
                new Object[] {"Remote", new String[] {"inner"}, "remote"});
        KetherScriptEngine engine = new KetherScriptEngine(registry);

        assertEquals("value", engine.eval(
                "inner value", ScriptContext.builder().namespaces("remote").build())
                .toCompletableFuture().join());

        FakeContainer replacement = new FakeContainer("Remote");
        replacement.resolveActions = true;
        discovery.containers.clear();
        discovery.containers.add(replacement);
        interop.refresh();
        TabooLibKetherInterop.call(
                TabooLibKetherInterop.ADD_ACTION,
                new Object[] {"Remote", new String[] {"inner"}, "remote"});

        assertEquals("value", engine.eval(
                "inner value", ScriptContext.builder().namespaces("remote").build())
                .toCompletableFuture().join());
        assertTrue(old.resolved.contains("inner"));
        assertTrue(replacement.resolved.contains("inner"));
        scope.close();
    }

    @Test
    void acceptsTheOpenResultMistakenlyPassedByTabooLib624ForExitStatus() {
        SimpleQuestService service = new SimpleQuestService();
        SimpleQuestContext context = service.newContext(service.load(
                "exit-test", "def main = { }"));
        FakeContainer remote = new FakeContainer("Remote");
        TabooLibKetherProtocol.LocalContextSource source =
                new TabooLibKetherProtocol.LocalContextSource(
                        remote, "KlibProvider", context);

        source.setExitStatus(new ForeignResult(
                new ForeignStatus(false, true, 42L)));

        assertEquals(new ExitStatus(false, true, 42L), context.getExitStatus().get());
        service.close();
    }

    private static final class FakeDiscovery
            implements TabooLibKetherInterop.ContainerDiscovery {
        private final List<FakeContainer> containers = new ArrayList<FakeContainer>();

        @Override public List<? extends OpenContainer> discover() {
            return new ArrayList<FakeContainer>(containers);
        }
        @Override public OpenContainer find(String name) {
            for (FakeContainer container : containers) {
                if (container.name().equals(name)) return container;
            }
            return null;
        }
    }

    private static final class FakeContainer implements OpenContainer {
        private final String name;
        private final List<Invocation> invocations = new ArrayList<Invocation>();
        private final List<String> resolved = new ArrayList<String>();
        private boolean resolveActions;
        private final CompletableFuture<Object> pending = new CompletableFuture<Object>();

        private FakeContainer(String name) {
            this.name = name;
        }

        @Override public String name() { return name; }

        @Override public OpenResult call(String channel, Object... data) {
            invocations.add(new Invocation(channel, data));
            if (TabooLibKetherInterop.REMOTE_RESOLVE.equals(channel) && resolveActions) {
                String action = String.valueOf(data[2]);
                resolved.add(action);
                QuestReader reader = (QuestReader) data[1];
                if ("outer".equals(action)) {
                    return OpenResult.successful(new NestedSource(reader.nextAction()));
                }
                if ("inner".equals(action)) {
                    return OpenResult.successful(new LiteralSource(reader.nextToken()));
                }
                if ("async".equals(action)) {
                    reader.nextToken();
                    return OpenResult.successful(new AsyncSource(pending));
                }
                return OpenResult.failed();
            }
            if (TabooLibKetherInterop.CREATE_FRAME.equals(channel)) {
                return OpenResult.successful(data[1]);
            }
            return OpenResult.successful();
        }

        private List<String> channelsFor(String action) {
            List<String> result = new ArrayList<String>();
            for (Invocation invocation : invocations) {
                if ((TabooLibKetherInterop.ADD_ACTION.equals(invocation.channel)
                        || TabooLibKetherInterop.REMOVE_ACTION.equals(invocation.channel))
                        && contains(invocation.data, action)) {
                    result.add(invocation.channel);
                }
            }
            return result;
        }

        private static boolean contains(Object[] data, String action) {
            for (Object value : data) {
                if (value instanceof String[]
                        && Arrays.asList((String[]) value).contains(action)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final class Invocation {
        private final String channel;
        private final Object[] data;
        private Invocation(String channel, Object[] data) {
            this.channel = channel;
            this.data = data;
        }
    }

    public static final class LiteralSource {
        private final Object value;
        public LiteralSource(Object value) { this.value = value; }
        public CompletableFuture<Object> process(Object frame) {
            return CompletableFuture.completedFuture(value);
        }
    }

    public static final class NestedSource {
        private final ParsedAction<?> nested;
        public NestedSource(ParsedAction<?> nested) { this.nested = nested; }
        public CompletableFuture<Object> process(Object frame) {
            Object child = ReflectiveTabooLibContainers.invoke(frame, "newFrame", nested);
            @SuppressWarnings("unchecked") CompletableFuture<Object> future =
                    (CompletableFuture<Object>) ReflectiveTabooLibContainers.invoke(child, "run");
            return future;
        }
    }

    public static final class AsyncSource {
        private final CompletableFuture<Object> future;
        public AsyncSource(CompletableFuture<Object> future) { this.future = future; }
        public CompletableFuture<Object> process(Object frame) { return future; }
    }

    private static final class OneTokenReader implements QuestReader {
        private final String token;
        private boolean read;
        private int mark;
        private OneTokenReader(String token) { this.token = token; }
        @Override public char peek() { return token.charAt(0); }
        @Override public char peek(int offset) { return token.charAt(offset); }
        @Override public int getIndex() { return read ? token.length() : 0; }
        @Override public void setIndex(int index) { read = index > 0; }
        @Override public int getMark() { return mark; }
        @Override public boolean hasNext() { return !read; }
        @Override public boolean hasLineBreakBeforeNextToken() { return false; }
        @Override public String nextToken() { read = true; return token; }
        @Override public void mark() { mark = getIndex(); }
        @Override public void reset() { setIndex(mark); }
        @Override public <T> ParsedAction<T> nextAction() {
            throw new UnsupportedOperationException();
        }
        @Override public <T> ParsedAction<T> nextAction(String namespace) {
            throw new UnsupportedOperationException();
        }
        @Override public void expect(String value) {
            if (!value.equals(nextToken())) throw new IllegalArgumentException(value);
        }
    }

    private static final class ForeignResult {
        private final boolean successful = true;
        private final Object value;
        private ForeignResult(Object value) { this.value = value; }
    }

    private static final class ForeignStatus {
        private final boolean running;
        private final boolean waiting;
        private final long startTime;
        private ForeignStatus(boolean running, boolean waiting, long startTime) {
            this.running = running;
            this.waiting = waiting;
            this.startTime = startTime;
        }
    }
}
