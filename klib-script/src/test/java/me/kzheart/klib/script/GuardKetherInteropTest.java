package me.kzheart.klib.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.logging.Logger;
import me.kzheart.klib.guard.PluginHost;
import me.kzheart.klib.guard.kether.KetherInteropBroker;
import me.kzheart.klib.guard.kether.KetherInteropEndpoint;
import me.kzheart.klib.guard.kether.KetherInteropPeer;
import me.kzheart.klib.guard.kether.KetherInteropProtocol;
import me.kzheart.klib.guard.kether.KetherInteropRegistration;
import me.kzheart.klib.guard.kether.KetherInteropResult;
import me.kzheart.klib.scope.ScopeImpl;
import me.kzheart.klib.script.kether.core.ParsedAction;
import me.kzheart.klib.script.kether.core.QuestAction;
import me.kzheart.klib.script.kether.core.QuestActionParser;
import me.kzheart.klib.script.kether.core.QuestContext;
import me.kzheart.klib.script.kether.core.QuestReader;
import org.bukkit.Server;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

class GuardKetherInteropTest {

    @Test
    void productActionsUseOpaqueHandlesAndScopeCloseInvalidatesGeneration() {
        ScopeImpl scope = new ScopeImpl("cloud");
        StatementRegistry registry = new StatementRegistry();
        FakeBroker broker = new FakeBroker();
        GuardKetherInterop.install(scope, registry, new FakeHost(broker));

        registry.registerShared(scope, "cloud", "echo", QuestActionParser.of(reader -> {
            final String value = reader.nextToken();
            return new QuestAction<Object>() {
                @Override
                public CompletableFuture<Object> process(QuestContext.Frame frame) {
                    return CompletableFuture.<Object>completedFuture(value);
                }
            };
        }));

        assertEquals(Arrays.asList("cloud:echo"), broker.registration.published);
        FakePeer consumer = new FakePeer("Consumer");
        long handle = broker.endpoint.resolve(
                consumer, consumer.name(), new OneTokenReader("hello"), "echo", "cloud");
        assertEquals("hello", broker.endpoint.process(
                consumer, consumer.name(), handle, new Object()).toCompletableFuture().join());

        scope.close();
        assertFalse(broker.registration.isActive());
        CompletionStage<Object> stale = broker.endpoint.process(
                consumer, consumer.name(), handle, new Object());
        assertThrows(CompletionException.class, () -> stale.toCompletableFuture().join());
    }

    @Test
    void externalActionsAreImportedAndRemovedWithoutLeakingForeignActionType() {
        ScopeImpl scope = new ScopeImpl("cloud");
        StatementRegistry registry = new StatementRegistry();
        FakeBroker broker = new FakeBroker();
        GuardKetherInterop interop = GuardKetherInterop.install(
                scope, registry, new FakeHost(broker));
        FakePeer external = new FakePeer("External");

        broker.endpoint.addActions(external, "external", new String[] {"upper"});
        Object value = new KetherScriptEngine(registry, interop, Runnable::run).eval(
                "upper hello",
                ScriptContext.builder().namespaces("external").build())
                .toCompletableFuture().join();
        assertEquals("HELLO", value);
        assertEquals(KetherInteropProtocol.PROVIDER_NAME, external.consumerName);
        assertTrue(registry.registeredNames().contains("external:upper"));

        broker.endpoint.removeActions(external, "external", new String[] {"upper"});
        assertFalse(registry.registeredNames().contains("external:upper"));
        scope.close();
    }

    @Test
    void inFlightFutureIsFailedWhenProductGenerationCloses() {
        ScopeImpl scope = new ScopeImpl("cloud");
        StatementRegistry registry = new StatementRegistry();
        FakeBroker broker = new FakeBroker();
        GuardKetherInterop.install(scope, registry, new FakeHost(broker));
        final CompletableFuture<Object> source = new CompletableFuture<Object>();
        registry.registerShared(scope, "cloud", "wait", QuestActionParser.of(reader ->
                new QuestAction<Object>() {
                    @Override
                    public CompletableFuture<Object> process(QuestContext.Frame frame) {
                        return source;
                    }
                }));

        FakePeer consumer = new FakePeer("Consumer");
        long handle = broker.endpoint.resolve(
                consumer, consumer.name(), new OneTokenReader("unused"), "wait", "cloud");
        CompletableFuture<Object> result = broker.endpoint.process(
                consumer, consumer.name(), handle, new Object()).toCompletableFuture();
        scope.close();

        assertTrue(result.isCompletedExceptionally());
        assertTrue(source.isCancelled());
    }

    @Test
    void productPrivateResultCannotEscapeTheGuardClassLoaderBoundary() {
        ScopeImpl scope = new ScopeImpl("cloud");
        StatementRegistry registry = new StatementRegistry();
        FakeBroker broker = new FakeBroker();
        GuardKetherInterop.install(scope, registry, new FakeHost(broker));
        registry.registerShared(scope, "cloud", "private", QuestActionParser.of(reader ->
                new QuestAction<Object>() {
                    @Override
                    public CompletableFuture<Object> process(QuestContext.Frame frame) {
                        return CompletableFuture.<Object>completedFuture(new ProductPrivateValue());
                    }
                }));

        FakePeer consumer = new FakePeer("Consumer");
        long handle = broker.endpoint.resolve(
                consumer, consumer.name(), new OneTokenReader("unused"), "private", "cloud");

        assertThrows(CompletionException.class, () -> broker.endpoint.process(
                consumer, consumer.name(), handle, new Object()).toCompletableFuture().join());
        scope.close();
    }

    private static final class ProductPrivateValue {
    }

    private static final class FakeBroker implements KetherInteropBroker {
        private KetherInteropEndpoint endpoint;
        private FakeRegistration registration;

        @Override
        public KetherInteropRegistration attach(
                String productId,
                long generation,
                ClassLoader productLoader,
                KetherInteropEndpoint value
        ) {
            assertEquals("example.product", productId);
            assertEquals(7L, generation);
            endpoint = value;
            registration = new FakeRegistration(value);
            return registration;
        }
    }

    private static final class FakeRegistration implements KetherInteropRegistration {
        private final KetherInteropEndpoint endpoint;
        private final List<String> published = new ArrayList<String>();
        private boolean active = true;

        private FakeRegistration(KetherInteropEndpoint endpoint) {
            this.endpoint = endpoint;
        }

        @Override
        public void publish(String namespace, String... actions) {
            for (String action : actions) {
                String key = namespace + ':' + action;
                if (!published.contains(key)) {
                    published.add(key);
                }
            }
        }

        @Override
        public void withdraw(String namespace, String... actions) {
            for (String action : actions) {
                published.remove(namespace + ':' + action);
            }
        }

        @Override
        public boolean isActive() {
            return active;
        }

        @Override
        public void close() {
            if (!active) {
                return;
            }
            active = false;
            published.clear();
            endpoint.close();
        }
    }

    private static final class FakePeer implements KetherInteropPeer {
        private final String name;
        private String consumerName;

        private FakePeer(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public KetherInteropResult call(String channel, Object... arguments) {
            if (KetherInteropProtocol.REMOTE_RESOLVE.equals(channel)) {
                consumerName = String.valueOf(arguments[0]);
                QuestReader reader = (QuestReader) arguments[1];
                return KetherInteropResult.successful(
                        new UpperSource(reader.nextToken()));
            }
            if (KetherInteropProtocol.CREATE_FRAME.equals(channel)) {
                return KetherInteropResult.successful(arguments[1]);
            }
            return KetherInteropResult.failed();
        }
    }

    public static final class UpperSource {
        private final String value;

        public UpperSource(String value) {
            this.value = value;
        }

        public CompletableFuture<Object> process(Object frame) {
            return CompletableFuture.<Object>completedFuture(
                    value.toUpperCase(java.util.Locale.ROOT));
        }
    }

    private static final class FakeHost implements PluginHost {
        private final KetherInteropBroker broker;

        private FakeHost(KetherInteropBroker broker) {
            this.broker = broker;
        }

        @Override public String productId() { return "example.product"; }
        @Override public long generation() { return 7L; }
        @Override public KetherInteropBroker ketherInteropBroker() { return broker; }
        @Override public JavaPlugin plugin() { return null; }
        @Override public Server server() { return null; }
        @Override public File dataFolder() { return null; }
        @Override public Logger logger() { return Logger.getAnonymousLogger(); }
    }

    private static final class OneTokenReader implements QuestReader {
        private final String token;
        private boolean read;
        private int mark;

        private OneTokenReader(String token) {
            this.token = token;
        }

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
            if (!value.equals(nextToken())) {
                throw new IllegalArgumentException(value);
            }
        }
    }
}
