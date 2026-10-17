package me.kzheart.klib.data.postgresql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.Collections;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import me.kzheart.klib.data.jdbc.JdbcDataSources;
import me.kzheart.klib.data.StorageException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "KLIB_POSTGRESQL_TEST_URL", matches = ".+")
class PostgreSqlNotificationsTest {
    @AfterEach void noListenerThreadsRemain() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (threads() > 0 && System.nanoTime() < deadline) Thread.sleep(10);
        assertEquals(0, threads());
    }

    @Test void committedSubscriptionReceivesPendingTransactionAndQuotesIdentifiers() throws Exception {
        String channel = "Quoted\";SELECT 1; -- 中文";
        CompletableFuture<PostgreSqlNotification> received = new CompletableFuture<>();
        CountDownLatch listening = new CountDownLatch(1);
        PostgreSqlListenSession session = null;
        try (Connection writer = connection()) {
            writer.setAutoCommit(false);
            notify(writer, channel, "pending-before-LISTEN");
            session = listen(channel, properties(), options(), new PostgreSqlNotificationListener() {
                public void onNotification(PostgreSqlNotification n) { received.complete(n); }
                public void onListening(long generation) { assertEquals(1, generation); listening.countDown(); }
            });
            await(session.ready()); assertTrue(listening.await(5, TimeUnit.SECONDS));
            writer.commit();
            PostgreSqlNotification n = await(received);
            assertEquals(channel, n.channel()); assertEquals("pending-before-LISTEN", n.payload()); assertTrue(n.processId() > 0);
            await(session.closeAsync()); await(session.closeAsync());
        } finally { if (session != null) await(session.closeAsync()); }
    }

    @Test void terminatedBackendReconnectsResubscribesAndSignalsFreshGeneration() throws Exception {
        String channel = channel(), app = channel(); Properties props = properties(); props.setProperty("ApplicationName", app);
        CountDownLatch second = new CountDownLatch(1); AtomicInteger generations = new AtomicInteger();
        CompletableFuture<String> notification = new CompletableFuture<>();
        PostgreSqlListenSession session = listen(channel, props, options(), new PostgreSqlNotificationListener() {
            public void onNotification(PostgreSqlNotification n) { notification.complete(n.payload()); }
            public void onListening(long generation) { generations.incrementAndGet(); if (generation == 2) second.countDown(); }
        });
        try {
            await(session.ready()); int first = pid(app);
            terminate(first); assertTrue(second.await(5, TimeUnit.SECONDS));
            assertNotEquals(first, pid(app));
            try (Connection writer = connection()) { notify(writer, channel, "after-reconnect"); }
            assertEquals("after-reconnect", await(notification)); assertEquals(2, generations.get());
        } finally { await(session.closeAsync()); }
        assertEquals(0, pid(app));
    }

    @Test void consecutiveRealBackendFailuresExhaustFiniteRetries() throws Exception {
        String app = channel(); Properties props = properties(); props.setProperty("ApplicationName", app);
        AtomicInteger generations = new AtomicInteger(), retries = new AtomicInteger(), terminal = new AtomicInteger();
        PostgreSqlListenOptions options = PostgreSqlListenOptions.builder().connectTimeout(Duration.ofSeconds(2))
            .pollTimeout(Duration.ofSeconds(10)).reconnectAttempts(2).backoff(Duration.ofMillis(20), Duration.ofMillis(40)).build();
        PostgreSqlListenSession session = listen(channel(), props, options, new PostgreSqlNotificationListener() {
            public void onNotification(PostgreSqlNotification n) { }
            public void onListening(long generation) {
                generations.incrementAndGet();
                try { terminate(pid(app)); } catch (Exception failure) { throw new AssertionError(failure); }
            }
            public void onFailure(Throwable failure, boolean end) { if (end) terminal.incrementAndGet(); else retries.incrementAndGet(); }
        });
        assertThrows(ExecutionException.class, () -> await(session.completion()));
        assertEquals(3, generations.get()); assertEquals(2, retries.get()); assertEquals(1, terminal.get());
        assertEquals(0, pid(app));
    }

    @Test void queueOverflowFailsExplicitlyAndAcceptedCallbackDrainsBeforeCompletion() throws Exception {
        String channel = channel(), app = channel(); Properties props = properties(); props.setProperty("ApplicationName", app);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1); AtomicInteger delivered = new AtomicInteger();
        PostgreSqlListenSession session = listen(channel, props, PostgreSqlListenOptions.builder().queueCapacity(1)
            .pollTimeout(Duration.ofMillis(20)).build(), new PostgreSqlNotificationListener() {
                public void onListening(long generation) { entered.countDown(); try { assertTrue(release.await(5, TimeUnit.SECONDS)); } catch (InterruptedException failure) { throw new AssertionError(failure); } }
                public void onNotification(PostgreSqlNotification n) { delivered.incrementAndGet(); }
            });
        try {
            await(session.ready()); assertTrue(entered.await(5, TimeUnit.SECONDS));
            try (Connection writer = connection()) { for (int i = 0; i < 4; i++) notify(writer, channel, "n" + i); }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (pid(app) != 0 && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(0, pid(app)); assertFalse(session.completion().toCompletableFuture().isDone());
            release.countDown();
            ExecutionException failed = assertThrows(ExecutionException.class, () -> await(session.completion()));
            assertInstanceOf(RejectedExecutionException.class, failed.getCause()); assertEquals(1, delivered.get());
        } finally { release.countDown(); session.closeAsync(); }
    }

    @Test void closeFromCallbackIsNonblockingAndDirectSelfWaitFailsInsteadOfDeadlocking() throws Exception {
        String channel = channel(); CountDownLatch assigned = new CountDownLatch(1);
        AtomicReference<PostgreSqlListenSession> reference = new AtomicReference<>();
        AtomicInteger guarded = new AtomicInteger();
        PostgreSqlListenSession session = listen(channel, properties(), options(), n -> {
            try { assertTrue(assigned.await(5, TimeUnit.SECONDS)); } catch (InterruptedException failure) { throw new AssertionError(failure); }
            CompletionStage<Void> closing = reference.get().closeAsync();
            assertThrows(IllegalStateException.class, () -> closing.toCompletableFuture().join()); guarded.incrementAndGet();
        });
        reference.set(session); assigned.countDown();
        try {
            await(session.ready()); try (Connection writer = connection()) { notify(writer, channel, "close"); }
            await(session.completion()); assertEquals(1, guarded.get());
        } finally { await(session.closeAsync()); }
    }

    @Test void closeInterruptsLongPollingAndReportsAStillBlockedCallbackAccurately() throws Exception {
        PostgreSqlListenSession longPoll = listen(channel(), properties(), PostgreSqlListenOptions.builder()
            .pollTimeout(Duration.ofSeconds(30)).build(), n -> { });
        await(longPoll.ready()); longPoll.closeAsync().toCompletableFuture().get(3, TimeUnit.SECONDS);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        PostgreSqlListenSession blocked = listen(channel(), properties(), PostgreSqlListenOptions.builder().closeTimeout(Duration.ofMillis(20)).build(),
            new PostgreSqlNotificationListener() {
                public void onNotification(PostgreSqlNotification n) { }
                public void onListening(long generation) {
                    entered.countDown();
                    try { assertTrue(release.await(5, TimeUnit.SECONDS)); } catch (InterruptedException failure) { throw new AssertionError(failure); }
                }
            });
        try {
            await(blocked.ready()); assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertThrows(StorageException.class, blocked::dispose);
            assertFalse(blocked.completion().toCompletableFuture().isDone());
        } finally { release.countDown(); await(blocked.closeAsync()); }
    }

    @Test void authenticationConfigurationFailureIsTerminalAndCloseBeforeReadyCleansUp() throws Exception {
        Properties invalid = properties(); invalid.setProperty("user", channel());
        AtomicInteger retry = new AtomicInteger();
        PostgreSqlListenSession bad = listen(channel(), invalid, options(), new PostgreSqlNotificationListener() {
            public void onNotification(PostgreSqlNotification n) { }
            public void onFailure(Throwable failure, boolean terminal) { if (!terminal) retry.incrementAndGet(); }
        });
        assertThrows(ExecutionException.class, () -> await(bad.ready()));
        assertThrows(ExecutionException.class, () -> await(bad.completion())); assertEquals(0, retry.get());
        String app = channel(); Properties props = properties(); props.setProperty("ApplicationName", app);
        PostgreSqlListenSession closing = listen(channel(), props, options(), n -> fail("closed listener notification"));
        await(closing.closeAsync()); assertTrue(closing.ready().toCompletableFuture().isDone()); assertEquals(0, pid(app));
    }

    private PostgreSqlListenSession listen(String channel, Properties props, PostgreSqlListenOptions options, PostgreSqlNotificationListener listener) {
        return PostgreSqlNotifications.listen(System.getenv("KLIB_POSTGRESQL_TEST_URL"), props, Collections.singleton(channel), options, listener);
    }
    private PostgreSqlListenOptions options() { return PostgreSqlListenOptions.builder().connectTimeout(Duration.ofSeconds(2)).pollTimeout(Duration.ofMillis(50))
        .backoff(Duration.ofMillis(20), Duration.ofMillis(100)).build(); }
    private static Properties properties() {
        Properties props = new Properties();
        String user = System.getenv("KLIB_POSTGRESQL_TEST_USER"), password = System.getenv("KLIB_POSTGRESQL_TEST_PASSWORD");
        if (user != null) props.setProperty("user", user); if (password != null) props.setProperty("password", password);
        return props;
    }
    private Connection connection() throws Exception { return JdbcDataSources.driver("org.postgresql.Driver", getClass().getClassLoader(), System.getenv("KLIB_POSTGRESQL_TEST_URL"), properties()).getConnection(); }
    private void notify(Connection writer, String channel, String payload) throws Exception {
        try (PreparedStatement statement = writer.prepareStatement("SELECT pg_notify(?, ?)")) { statement.setString(1, channel); statement.setString(2, payload); statement.execute(); }
    }
    private int pid(String app) throws Exception {
        try (Connection control = connection(); PreparedStatement statement = control.prepareStatement("SELECT pid FROM pg_stat_activity WHERE application_name = ?")) {
            statement.setString(1, app);
            try (ResultSet rows = statement.executeQuery()) { if (!rows.next()) return 0; int pid = rows.getInt(1); assertFalse(rows.next()); return pid; }
        }
    }
    private void terminate(int pid) throws Exception {
        assertTrue(pid > 0);
        try (Connection control = connection(); PreparedStatement statement = control.prepareStatement("SELECT pg_terminate_backend(?)")) {
            statement.setInt(1, pid); try (ResultSet rows = statement.executeQuery()) { assertTrue(rows.next()); assertTrue(rows.getBoolean(1)); }
        }
    }
    private static String channel() { return "klib_" + UUID.randomUUID().toString().replace("-", ""); }
    private static <T> T await(CompletionStage<T> stage) throws Exception { return stage.toCompletableFuture().get(15, TimeUnit.SECONDS); }
    private int threads() { return (int) Thread.getAllStackTraces().keySet().stream().filter(t -> t.isAlive() && t.getName().startsWith("klib-pg-listen-")).count(); }
}
