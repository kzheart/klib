package me.kzheart.klib.data.postgresql;

import me.kzheart.klib.data.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class PostgreSqlStorageProviderTest {
    @Test void rejectsOtherBackendsBeforeOpening() {
        assertThrows(IllegalArgumentException.class, () -> new PostgreSqlStorageProvider("jdbc:mysql://localhost/db", "", ""));
        assertThrows(IllegalArgumentException.class, () -> new PostgreSqlStorageProvider(" ", "", ""));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "KLIB_POSTGRESQL_TEST_URL", matches = ".+")
    void realPostgreSqlPersistsBytesAndRollsBackDataAndMigrationVersion() throws Exception {
        String namespace = "test-" + UUID.randomUUID();
        String url = System.getenv("KLIB_POSTGRESQL_TEST_URL");
        String user = System.getenv("KLIB_POSTGRESQL_TEST_USER");
        String password = System.getenv("KLIB_POSTGRESQL_TEST_PASSWORD");
        StorageProvider provider = new PostgreSqlStorageProvider(url, user, password);
        StorageSession session = provider.open().toCompletableFuture().get(15, TimeUnit.SECONDS);
        try {
            byte[] value = "中文😀\u0000binary".getBytes(StandardCharsets.UTF_8);
            session.put(namespace, "quoted'key", value).toCompletableFuture().get();
            Arrays.fill(value, (byte) 0);
            assertArrayEquals("中文😀\u0000binary".getBytes(StandardCharsets.UTF_8), session.get(namespace, "quoted'key").toCompletableFuture().get().get());
            session.put(namespace, "quoted'key", new byte[]{0, -1, 42}).toCompletableFuture().get();
            assertArrayEquals(new byte[]{0, -1, 42}, session.entries(namespace).toCompletableFuture().get().get("quoted'key"));
            assertThrows(ExecutionException.class, () -> session.transaction(tx -> {
                tx.put(namespace, "rollback", new byte[]{1});
                tx.schemaVersion(namespace, 9);
                throw new IllegalStateException("rollback fixture");
            }).toCompletableFuture().get());
            assertFalse(session.get(namespace, "rollback").toCompletableFuture().get().isPresent());
            assertEquals(Integer.valueOf(0), session.transaction(tx -> tx.schemaVersion(namespace)).toCompletableFuture().get());
            Schema schema = new Schema(namespace, Arrays.asList(
                    new Migration(1, tx -> tx.put(namespace, "migration", new byte[]{1})),
                    new Migration(2, tx -> tx.put(namespace, "migration", new byte[]{2}))));
            assertEquals(Integer.valueOf(2), MigrationRunner.apply(session, schema).toCompletableFuture().get());
            assertEquals(Integer.valueOf(2), MigrationRunner.apply(session, schema).toCompletableFuture().get());
            session.delete(namespace, "migration").toCompletableFuture().get();
            assertFalse(session.get(namespace, "migration").toCompletableFuture().get().isPresent());
        } finally {
            session.dispose();
            provider.dispose();
        }
        provider = new PostgreSqlStorageProvider(url, user, password);
        StorageSession reopened = provider.open().toCompletableFuture().get(15, TimeUnit.SECONDS);
        try {
            assertArrayEquals(new byte[]{0, -1, 42}, reopened.get(namespace, "quoted'key").toCompletableFuture().get().get());
            reopened.delete(namespace, "quoted'key").toCompletableFuture().get();
        } finally { reopened.dispose(); provider.dispose(); }
    }
}
