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
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MigrationRollbackTest {
    @TempDir
    Path temporaryDirectory;

    Stream<Arguments> providers() {
        return Stream.of(
                Arguments.of("sqlite", (Factory) () -> new SQLiteStorageProvider(temporaryDirectory.resolve("rollback.sqlite"))),
                Arguments.of("mysql", (Factory) () -> new MySqlStorageProvider(
                        "jdbc:h2:mem:rollback_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "")),
                Arguments.of("json", (Factory) () -> new JsonStorageProvider(temporaryDirectory.resolve("rollback.json")))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("providers")
    void failedMigrationRollsBackWritesAndVersion(String name, Factory factory) throws Exception {
        StorageProvider provider = factory.create();
        StorageSession session = provider.open().toCompletableFuture().get();
        try {
            Schema schema = new Schema("broken", Arrays.asList(new Migration(1, context -> {
                context.put("migration", "partial", "bad".getBytes(StandardCharsets.UTF_8));
                throw new IllegalStateException("boom");
            })));

            assertThrows(ExecutionException.class, () -> MigrationRunner.apply(session, schema).toCompletableFuture().get());
            assertFalse(session.get("migration", "partial").toCompletableFuture().get().isPresent());
            assertEquals(0, session.transaction(context -> context.schemaVersion("broken")).toCompletableFuture().get());
        } finally {
            session.dispose();
            provider.dispose();
        }
    }

    private interface Factory {
        StorageProvider create();
    }
}
