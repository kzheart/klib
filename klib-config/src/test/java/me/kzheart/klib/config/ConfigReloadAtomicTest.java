package me.kzheart.klib.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import me.kzheart.klib.config.api.ConfigDocument;
import me.kzheart.klib.scope.Disposable;
import me.kzheart.klib.scope.ScopeImpl;
import me.kzheart.klib.scope.capability.ConfigCapability;
import org.junit.jupiter.api.Test;

class ConfigReloadAtomicTest {
    @Test
    void failedReloadKeepsOldValueAndWatcherDiesWithScope() {
        InMemoryConfigSource source = new InMemoryConfigSource("config.yml", "port: 25565\n");
        ScopeImpl scope = new ScopeImpl("config");
        scope.registerCapability(
                ConfigCapability.class,
                new YamlConfigCapability((owner, path) -> source, new YamlConfigMapper()));
        ConfigDocument<ServerConfig> document = scope.config(ServerConfig.class, "config.yml");
        AtomicReference<Throwable> observedFailure = new AtomicReference<Throwable>();
        ConfigErrors.onReloadFailure(document, observedFailure::set);
        AtomicInteger changes = new AtomicInteger();
        document.onChange(changes::incrementAndGet);

        source.update("port: 25566\n");
        source.signalChange();
        assertEquals(25566, document.value().port);
        assertEquals(1, changes.get());

        source.update("port: invalid\n");
        source.signalChange();
        assertEquals(25566, document.value().port);
        assertEquals(1, changes.get());
        assertTrue(((YamlConfigDocument<ServerConfig>) document).lastFailure().isPresent());
        assertTrue(observedFailure.get() instanceof ConfigException);

        scope.close();
        source.update("port: 25567\n");
        source.signalChange();
        assertEquals(25566, document.value().port);
        assertThrows(IllegalStateException.class, document::reload);
    }

    @Test
    void successfulManualReloadClearsPreviousFailure() {
        InMemoryConfigSource source = new InMemoryConfigSource("config.yml", "port: 1\n");
        ScopeImpl scope = new ScopeImpl("config");
        YamlConfigDocument<ServerConfig> document = YamlConfigDocument.open(
                scope, source, new YamlConfigMapper(), ServerConfig.class);
        source.update("port: bad\n");
        source.signalChange();
        assertTrue(document.lastFailure().isPresent());

        source.update("port: 2\n");
        document.reload();

        assertEquals(2, document.value().port);
        assertFalse(document.lastFailure().isPresent());
        scope.close();
    }

    @Test
    void diagnosticSnapshotUsesCachedStateWithoutExposingConfigValue() {
        InMemoryConfigSource source = new InMemoryConfigSource("config.yml", "port: 1\n");
        ScopeImpl scope = new ScopeImpl("diagnostic-config");
        YamlConfigDocument<ServerConfig> document = YamlConfigDocument.open(
                scope, source, new YamlConfigMapper(), ServerConfig.class);

        java.util.Map<String, ?> snapshot = document.diagnosticSnapshot();

        assertEquals("config.yml", snapshot.get("source"));
        assertEquals(ServerConfig.class.getName(), snapshot.get("value_type"));
        assertFalse(snapshot.containsKey("value"));
        scope.close();
    }

    @Test
    void duplicateWatcherSignalDoesNotNotifyListeners() {
        InMemoryConfigSource source = new InMemoryConfigSource("config.yml", "port: 1\n");
        ScopeImpl scope = new ScopeImpl("duplicate-watch-event");
        YamlConfigDocument<ServerConfig> document = YamlConfigDocument.open(
                scope, source, new YamlConfigMapper(), ServerConfig.class);
        AtomicInteger changes = new AtomicInteger();
        document.onChange(changes::incrementAndGet);

        source.signalChange();
        assertEquals(0, changes.get());

        source.update("port: 2\n");
        source.signalChange();
        assertEquals(1, changes.get());
        assertEquals(2, document.value().port);
        scope.close();
    }

    @Test
    void serializesCompleteReloadSoOlderCandidateCannotWin() throws Exception {
        BlockingConfigSource source = new BlockingConfigSource("value: initial\n");
        ScopeImpl scope = new ScopeImpl("concurrent-reload");
        YamlConfigDocument<TextConfig> document = YamlConfigDocument.open(
                scope, source, new YamlConfigMapper(), TextConfig.class);
        document.onChange(() -> {
            if ("new".equals(document.value().value)) {
                source.newValueObserved.countDown();
            }
        });
        source.update("value: old\n");
        source.armOldCandidate();
        AtomicReference<Throwable> firstFailure = new AtomicReference<Throwable>();
        AtomicReference<Throwable> secondFailure = new AtomicReference<Throwable>();
        Thread first = reloadThread(document, firstFailure, "first-reload");
        first.start();
        assertTrue(source.oldPrepared.await(5, TimeUnit.SECONDS));

        source.update("value: new\n");
        Thread second = reloadThread(document, secondFailure, "second-reload");
        second.start();
        source.releaseOld.countDown();
        first.join(5000L);
        second.join(5000L);

        assertFalse(first.isAlive());
        assertFalse(second.isAlive());
        assertNull(firstFailure.get());
        assertNull(secondFailure.get());
        assertEquals("new", document.value().value);
        scope.close();
    }

    @Test
    void listenerFailureDoesNotTurnSuccessfulReloadIntoFailure() {
        InMemoryConfigSource source = new InMemoryConfigSource("config.yml", "port: 1\n");
        ScopeImpl scope = new ScopeImpl("listener-failure");
        YamlConfigDocument<ServerConfig> document = YamlConfigDocument.open(
                scope, source, new YamlConfigMapper(), ServerConfig.class);
        document.onChange(() -> {
            throw new IllegalStateException("listener failed");
        });
        source.update("port: 2\n");

        assertDoesNotThrow(document::reload);
        assertEquals(2, document.value().port);
        assertFalse(document.lastFailure().isPresent());
        assertTrue(document.lastListenerFailure().isPresent());
        scope.close();
    }

    @Test
    void listenerFailureIsLoggedWithStackTrace() {
        InMemoryConfigSource source = new InMemoryConfigSource("config.yml", "port: 1\n");
        ScopeImpl scope = new ScopeImpl("listener-failure-logging");
        Logger logger = Logger.getLogger(YamlConfigDocument.class.getName());
        List<LogRecord> records = new ArrayList<LogRecord>();
        Handler collector = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(collector);
        try {
            YamlConfigDocument<ServerConfig> document = YamlConfigDocument.open(
                    scope, source, new YamlConfigMapper(), ServerConfig.class);
            document.onChange(() -> {
                throw new IllegalStateException("listener failed");
            });

            source.update("port: 2\n");
            source.signalChange();

            assertTrue(document.lastListenerFailure().isPresent());
            LogRecord logged = null;
            for (LogRecord record : records) {
                if (record.getThrown() instanceof ConfigException) {
                    logged = record;
                }
            }
            assertNotNull(logged);
            assertEquals(Level.SEVERE, logged.getLevel());
            assertTrue(logged.getMessage().contains("config.yml"));
            assertEquals(1, logged.getThrown().getSuppressed().length);
        } finally {
            logger.removeHandler(collector);
            scope.close();
        }
    }

    private static Thread reloadThread(
            YamlConfigDocument<?> document,
            AtomicReference<Throwable> failure,
            String name
    ) {
        return new Thread(() -> {
            try {
                document.reload();
            } catch (Throwable caught) {
                failure.set(caught);
            }
        }, name);
    }

    static final class ServerConfig {
        private int port;
    }

    static final class TextConfig {
        private String value;
    }

    private static final class BlockingConfigSource implements ConfigSource {
        private final CountDownLatch oldPrepared = new CountDownLatch(1);
        private final CountDownLatch releaseOld = new CountDownLatch(1);
        private final CountDownLatch newValueObserved = new CountDownLatch(1);
        private final AtomicBoolean blockOld = new AtomicBoolean();
        private volatile String content;

        private BlockingConfigSource(String content) {
            this.content = content;
        }

        @Override
        public String sourceName() {
            return "blocking.yml";
        }

        @Override
        public PreparedConfig prepare() {
            final String preparedContent = content;
            if (preparedContent.contains("old") && blockOld.compareAndSet(true, false)) {
                oldPrepared.countDown();
                await(releaseOld, 5L, TimeUnit.SECONDS);
            }
            final YamlDocument document = YamlDocument.parse(sourceName(), preparedContent);
            return new PreparedConfig() {
                @Override
                public YamlDocument document() {
                    return document;
                }

                @Override
                public void commit() {
                    if (preparedContent.contains("old")) {
                        await(newValueObserved, 500L, TimeUnit.MILLISECONDS);
                    }
                }
            };
        }

        @Override
        public Disposable watch(Runnable listener) {
            return () -> { };
        }

        private void update(String updated) {
            content = updated;
        }

        private void armOldCandidate() {
            blockOld.set(true);
        }

        private static void await(CountDownLatch latch, long timeout, TimeUnit unit) {
            try {
                latch.await(timeout, unit);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while coordinating reload test", failure);
            }
        }
    }
}
