package me.kzheart.klib.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import me.kzheart.klib.scope.ScopeImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RegistryReloadTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void validatesUniqueIdsAndReplacesSnapshotOnlyAfterCompleteLoad() throws Exception {
        write("first.yml", "id: first\nvalue: 1\n");
        write("second.yml", "id: second\nvalue: 2\n");
        ScopeImpl scope = new ScopeImpl("registry");
        Registry<Entry> registry = Registry.open(
                scope,
                temporaryDirectory,
                Entry.class,
                entry -> entry.id,
                new YamlConfigMapper(),
                false);
        AtomicInteger changes = new AtomicInteger();
        AtomicReference<Throwable> reloadFailure = new AtomicReference<Throwable>();
        registry.onChange(changes::incrementAndGet);
        registry.onReloadFailure(reloadFailure::set);
        Map<String, Entry> firstSnapshot = registry.snapshot();

        assertEquals(2, firstSnapshot.size());
        assertEquals(1, firstSnapshot.get("first").value);

        write("second.yml", "id: first\nvalue: 99\n");
        assertThrows(ConfigException.class, registry::reload);
        assertTrue(firstSnapshot == registry.snapshot());
        assertEquals(0, changes.get());
        assertTrue(reloadFailure.get() instanceof ConfigException);

        write("second.yml", "id: second\nvalue: 3\n");
        registry.reload();
        assertEquals(3, registry.snapshot().get("second").value);
        assertEquals(1, changes.get());

        scope.close();
        assertThrows(IllegalStateException.class, registry::reload);
    }

    @Test
    void directoryWatcherReloadsSnapshotAndStopsWithScope() throws Exception {
        write("entry.yml", "id: watched\nvalue: 1\n");
        ScopeImpl scope = new ScopeImpl("registry-watch");
        Registry<Entry> registry = Registry.open(
                scope,
                temporaryDirectory,
                Entry.class,
                entry -> entry.id,
                new YamlConfigMapper());
        CountDownLatch changed = new CountDownLatch(1);
        registry.onChange(changed::countDown);

        Path temporary = temporaryDirectory.resolve("replacement.tmp");
        Files.write(temporary, "id: watched\nvalue: 7\n".getBytes(StandardCharsets.UTF_8));
        Files.move(
                temporary,
                temporaryDirectory.resolve("entry.yml"),
                StandardCopyOption.REPLACE_EXISTING);

        assertTrue(changed.await(5, TimeUnit.SECONDS));
        assertEquals(7, registry.snapshot().get("watched").value);
        scope.close();
    }

    @Test
    void listenerFailureIsReportedSeparatelyFromSuccessfulReload() throws Exception {
        write("entry.yml", "id: one\nvalue: 1\n");
        ScopeImpl scope = new ScopeImpl("registry-listener-failure");
        Registry<Entry> registry = Registry.open(
                scope,
                temporaryDirectory,
                Entry.class,
                entry -> entry.id,
                new YamlConfigMapper(),
                false);
        registry.onChange(() -> {
            throw new IllegalStateException("listener failed");
        });
        write("entry.yml", "id: one\nvalue: 2\n");

        assertDoesNotThrow(registry::reload);
        assertEquals(2, registry.find("one").get().value);
        assertFalse(registry.lastFailure().isPresent());
        assertTrue(registry.lastListenerFailure().isPresent());
        scope.close();
    }

    private void write(String name, String content) throws Exception {
        Files.write(
                temporaryDirectory.resolve(name),
                content.getBytes(StandardCharsets.UTF_8));
    }

    static final class Entry {
        private String id;
        private int value;
    }
}
