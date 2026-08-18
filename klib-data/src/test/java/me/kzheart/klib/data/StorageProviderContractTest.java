package me.kzheart.klib.data;

import me.kzheart.klib.data.json.JsonStorageProvider;
import me.kzheart.klib.data.sql.MySqlStorageProvider;
import me.kzheart.klib.data.sql.SQLiteStorageProvider;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StorageProviderContractTest {
    @TempDir
    Path temporaryDirectory;

    Stream<Arguments> providers() {
        return Stream.of(
                Arguments.of("sqlite", (Factory) () -> new SQLiteStorageProvider(temporaryDirectory.resolve("contract.sqlite"))),
                Arguments.of("mysql", (Factory) () -> new MySqlStorageProvider(
                        "jdbc:h2:mem:contract_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "")),
                Arguments.of("json", (Factory) () -> new JsonStorageProvider(temporaryDirectory.resolve("contract.json")))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("providers")
    void allProvidersHonorKvAndMigrationContract(String name, Factory factory) throws Exception {
        StorageProvider provider = factory.create();
        StorageSession session = provider.open().toCompletableFuture().get(5, TimeUnit.SECONDS);
        try {
            String callerThread = Thread.currentThread().getName();
            AtomicReference<String> storageThread = new AtomicReference<String>();
            session.transaction(context -> {
                storageThread.set(Thread.currentThread().getName());
                return null;
            }).toCompletableFuture().get();
            assertFalse(callerThread.equals(storageThread.get()));

            byte[] original = bytes("first");
            session.put("players", "alice", original).toCompletableFuture().get(5, TimeUnit.SECONDS);
            Arrays.fill(original, (byte) 0);
            assertArrayEquals(bytes("first"), session.get("players", "alice").toCompletableFuture().get().get());

            session.put("players", "alice", bytes("updated")).toCompletableFuture().get();
            session.put("players", "bob", bytes("second")).toCompletableFuture().get();
            Map<String, byte[]> entries = session.entries("players").toCompletableFuture().get();
            assertEquals(2, entries.size());
            assertArrayEquals(bytes("updated"), entries.get("alice"));

            Schema schema = new Schema("contract", Arrays.asList(
                    new Migration(1, context -> context.put("meta", "one", bytes("1"))),
                    new Migration(2, context -> context.put("meta", "two", bytes("2")))
            ));
            assertEquals(2, MigrationRunner.apply(session, schema).toCompletableFuture().get());
            assertEquals(2, MigrationRunner.apply(session, schema).toCompletableFuture().get());
            assertTrue(session.get("meta", "two").toCompletableFuture().get().isPresent());

            session.delete("players", "alice").toCompletableFuture().get();
            assertFalse(session.get("players", "alice").toCompletableFuture().get().isPresent());
        } finally {
            session.dispose();
            provider.dispose();
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private interface Factory {
        StorageProvider create();
    }
}
