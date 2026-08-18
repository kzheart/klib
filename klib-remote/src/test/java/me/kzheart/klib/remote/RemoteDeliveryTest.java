package me.kzheart.klib.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RemoteDeliveryTest {
    @TempDir Path temporaryDirectory;

    @Test
    void generatedEventIdCannotBeOverriddenAndRemainsStable() {
        Map<String, Object> fields = new LinkedHashMap<String, Object>();
        fields.put("event_id", "caller-controlled");
        fields.put("message", "complete body");
        RemoteEvent event = RemoteEvent.of("log", fields);

        assertNotEquals("caller-controlled", event.toMap().get("event_id"));
        assertEquals(event.toMap().get("event_id"), event.toMap().get("event_id"));
    }

    @Test
    void eventDeeplySnapshotsNestedJsonValues() {
        Map<String, Object> nested = new LinkedHashMap<String, Object>();
        nested.put("value", "before");
        RemoteEvent event = RemoteEvent.of("log",
                Collections.<String, Object>singletonMap("payload", nested));
        nested.put("value", "after");

        assertEquals("before", ((Map<?, ?>) event.toMap().get("payload")).get("value"));
    }

    @Test
    void callerNeverRunsNetworkAndFailuresRemainIsolated() throws Exception {
        String callerThread = Thread.currentThread().getName();
        AtomicReference<String> transportThread = new AtomicReference<String>();
        CountDownLatch attempted = new CountDownLatch(1);
        RpkTransport transport = new RpkTransport() {
            @Override public String settings() { return settingsResponse(); }
            @Override public String sendBatch(byte[] json) throws IOException {
                transportThread.set(Thread.currentThread().getName());
                attempted.countDown();
                throw new IOException("offline");
            }
        };
        RemoteDelivery delivery = delivery(transport, temporaryDirectory)
                .initialBackoff(Duration.ofMillis(10))
                .maxBackoff(Duration.ofMillis(20))
                .build();
        try {
            delivery.accept(log("caller continues"));
            assertTrue(attempted.await(5, TimeUnit.SECONDS));
            assertNotEquals(callerThread, transportThread.get());
            assertTrue(delivery.queuedEvents() >= 1);
        } finally {
            delivery.dispose();
        }
    }

    @Test
    void semanticallyInvalidReceiptRetainsWholeBatchForIdempotentRetry() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        List<String> bodies = Collections.synchronizedList(new ArrayList<String>());
        CountDownLatch retried = new CountDownLatch(1);
        RpkTransport transport = new RpkTransport() {
            @Override public String settings() { return settingsResponse(); }
            @Override public String sendBatch(byte[] json) throws IOException {
                bodies.add(new String(json, StandardCharsets.UTF_8));
                if (calls.getAndIncrement() == 0) {
                    return successfulReceiptWithError(json);
                }
                retried.countDown();
                return accepted(json);
            }
        };
        RemoteDelivery delivery = delivery(transport, temporaryDirectory)
                .batchSize(4)
                .initialBackoff(Duration.ofMillis(5))
                .maxBackoff(Duration.ofMillis(10))
                .build();
        try {
            delivery.accept(log("first"));
            delivery.accept(log("second"));
            delivery.accept(log("third"));
            delivery.accept(log("must survive partial receipt"));
            assertTrue(retried.await(5, TimeUnit.SECONDS));
            assertEquals(bodies.get(0), bodies.get(1));
            assertTrue(bodies.get(1).contains("must survive partial receipt"));
        } finally {
            delivery.dispose();
        }
    }

    @Test
    void mixedAcceptedDuplicateAndPermanentRejectedResultsAllLeaveQueue() throws Exception {
        CountDownLatch attempted = new CountDownLatch(1);
        RpkTransport transport = new RpkTransport() {
            @Override public String settings() { return settingsResponse(); }
            @Override public String sendBatch(byte[] json) {
                attempted.countDown();
                return mixedReceipt(json);
            }
        };
        RemoteDelivery delivery = delivery(transport, temporaryDirectory).batchSize(3).build();
        try {
            delivery.accept(log("accepted"));
            delivery.accept(log("duplicate"));
            delivery.accept(log("permanently rejected"));
            assertTrue(attempted.await(5, TimeUnit.SECONDS));
            await(() -> delivery.queuedEvents() == 0L, 5000L);
        } finally {
            delivery.dispose();
        }
    }

    @Test
    void retryKeepsCompleteBodyAndStableEventId() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        List<String> bodies = Collections.synchronizedList(new ArrayList<String>());
        CountDownLatch delivered = new CountDownLatch(1);
        RpkTransport transport = new RpkTransport() {
            @Override public String settings() { return settingsResponse(); }
            @Override public String sendBatch(byte[] json) throws IOException {
                bodies.add(new String(json, StandardCharsets.UTF_8));
                if (calls.getAndIncrement() == 0) throw new RemoteHttpException(503, -1L);
                delivered.countDown();
                return accepted(json);
            }
        };
        RemoteDelivery delivery = delivery(transport, temporaryDirectory)
                .maxEventBytes(256 * 1024)
                .initialBackoff(Duration.ofMillis(5))
                .maxBackoff(Duration.ofMillis(10))
                .build();
        String message = repeat('x', 128 * 1024);
        try {
            RemoteEvent event = log(message);
            String eventId = String.valueOf(event.toMap().get("event_id"));
            delivery.accept(event);
            assertTrue(delivered.await(5, TimeUnit.SECONDS));
            assertEquals(bodies.get(0), bodies.get(1));
            assertTrue(bodies.get(1).contains(eventId));
            assertTrue(bodies.get(1).contains(message));
        } finally {
            delivery.dispose();
        }
    }

    @Test
    void corruptQueueFileIsQuarantinedWithoutAllocationFromDeclaredLength() throws Exception {
        Path queue = temporaryDirectory.resolve("queue");
        Files.createDirectories(queue);
        Files.write(queue.resolve("attacker.rqe"), new byte[] {
                0x4b, 0x52, 0x51, 0x31, 1, 0, 0, 0, 0, 0, 0, 0, 0, 1,
                0x7f, (byte) 0xff, (byte) 0xff, (byte) 0xff
        });
        RemoteDelivery delivery = delivery(new AcceptingTransport(), queue).build();
        try {
            await(() -> Files.isDirectory(queue.resolve("quarantine")), 5000L);
            try (java.util.stream.Stream<Path> files = Files.list(queue.resolve("quarantine"))) {
                assertTrue(files.findAny().isPresent());
            }
        } finally {
            delivery.dispose();
        }
    }

    @Test
    void pausedKeyParksQueueInsteadOfHotRetrying() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch attempted = new CountDownLatch(1);
        RpkTransport transport = new RpkTransport() {
            @Override public String settings() { return settingsResponse(); }
            @Override public String sendBatch(byte[] json) throws IOException {
                calls.incrementAndGet();
                attempted.countDown();
                throw new RemoteHttpException(403, -1L);
            }
        };
        RemoteDelivery delivery = delivery(transport, temporaryDirectory)
                .initialBackoff(Duration.ofMillis(1)).maxBackoff(Duration.ofMillis(2)).build();
        try {
            delivery.accept(log("park me"));
            assertTrue(attempted.await(5, TimeUnit.SECONDS));
            Thread.sleep(100L);
            assertEquals(1, calls.get());
            assertEquals(1L, delivery.queuedEvents());
        } finally {
            delivery.dispose();
        }
    }

    @Test
    void invalidKeyStopsDeliveryAndRetainsDurableQueue() throws Exception {
        CountDownLatch attempted = new CountDownLatch(1);
        RpkTransport transport = new RpkTransport() {
            @Override public String settings() { return settingsResponse(); }
            @Override public String sendBatch(byte[] json) throws IOException {
                attempted.countDown();
                throw new RemoteHttpException(401, -1L);
            }
        };
        RemoteDelivery delivery = delivery(transport, temporaryDirectory).build();
        try {
            delivery.accept(log("retain me"));
            assertTrue(attempted.await(5, TimeUnit.SECONDS));
            await(() -> !delivery.isActive(), 5000L);
            assertFalse(delivery.submit(log("must be refused"))
                    .toCompletableFuture().get(1, TimeUnit.SECONDS));
            assertEquals(1L, delivery.queuedEvents());
        } finally {
            delivery.dispose();
        }
    }

    @Test
    void refreshedServerBudgetDropsOversizedEventWithoutSendingOrTruncating() throws Exception {
        AtomicInteger sends = new AtomicInteger();
        RpkTransport transport = new RpkTransport() {
            @Override public String settings() { return tightSettingsResponse(); }
            @Override public String sendBatch(byte[] json) {
                sends.incrementAndGet();
                return accepted(json);
            }
        };
        RemoteClient client = new RemoteClient(transport,
                RemoteCapabilities.builder().logs(true).exceptions(true).build(),
                () -> "inst-test", new RemoteEnvironment("1", "Paper", "8", "Linux"));
        client.refreshPolicy();
        assertEquals(1024, client.settings().limits().maxEventBytes());
        RemoteDelivery delivery = RemoteDelivery.builder(client, temporaryDirectory)
                .maxEventBytes(256 * 1024)
                .build();
        try {
            CompletableFuture<Boolean> persisted = delivery
                    .submit(log(repeat('x', 2048))).toCompletableFuture();
            assertFalse(persisted.get(5, TimeUnit.SECONDS));
            assertEquals(0L, delivery.queuedEvents());
            assertEquals(0, sends.get());
            assertEquals(1L, delivery.droppedEvents());
        } finally {
            delivery.dispose();
        }
    }

    @Test
    void tightenedServerBudgetDropsPersistedOversizedEventWithoutChangingPersistenceResult()
            throws Exception {
        AtomicInteger settingsCalls = new AtomicInteger();
        AtomicInteger sends = new AtomicInteger();
        CountDownLatch failedRefreshStarted = new CountDownLatch(1);
        CountDownLatch allowFailedRefresh = new CountDownLatch(1);
        CountDownLatch tightRefreshStarted = new CountDownLatch(1);
        CountDownLatch allowTightRefresh = new CountDownLatch(1);
        RpkTransport transport = new RpkTransport() {
            @Override public String settings() throws IOException {
                int call = settingsCalls.incrementAndGet();
                if (call == 1) {
                    return settingsResponse().replace(
                            "\"accepting_events\":true", "\"accepting_events\":false");
                }
                if (call == 2) {
                    failedRefreshStarted.countDown();
                    await(allowFailedRefresh);
                    throw new IOException("temporary settings failure");
                }
                if (call == 3) {
                    tightRefreshStarted.countDown();
                    await(allowTightRefresh);
                }
                return tightSettingsResponse();
            }

            @Override public String sendBatch(byte[] json) {
                sends.incrementAndGet();
                return accepted(json);
            }
        };
        RemoteClient client = new RemoteClient(transport,
                RemoteCapabilities.builder().logs(true).exceptions(true).build(),
                () -> "inst-test", new RemoteEnvironment("1", "Paper", "8", "Linux"));
        client.refreshPolicy();
        assertEquals(256 * 1024, client.settings().limits().maxEventBytes());
        RemoteDelivery delivery = RemoteDelivery.builder(client, temporaryDirectory)
                .maxEventBytes(256 * 1024)
                .initialBackoff(Duration.ofMillis(1))
                .maxBackoff(Duration.ofMillis(1))
                .settingsRefreshInterval(Duration.ofMillis(1))
                .build();
        try {
            assertTrue(failedRefreshStarted.await(5, TimeUnit.SECONDS));
            CompletableFuture<Boolean> persisted = delivery
                    .submit(log(repeat('x', 2048))).toCompletableFuture();
            assertFalse(persisted.isDone());

            allowFailedRefresh.countDown();
            assertTrue(persisted.get(5, TimeUnit.SECONDS));
            assertTrue(tightRefreshStarted.await(5, TimeUnit.SECONDS));
            assertEquals(1L, delivery.queuedEvents());
            assertEquals(0L, delivery.droppedEvents());

            allowTightRefresh.countDown();
            await(() -> delivery.droppedEvents() == 1L, 5000L);
            assertEquals(0L, delivery.queuedEvents());
            assertEquals(0, sends.get());
        } finally {
            allowFailedRefresh.countDown();
            allowTightRefresh.countDown();
            delivery.dispose();
        }
    }

    private RemoteDelivery.Builder delivery(RpkTransport transport, Path directory) {
        return RemoteDelivery.builder(new RemoteClient(transport,
                RemoteCapabilities.builder().logs(true).exceptions(true).build(),
                () -> "inst-test", new RemoteEnvironment("1", "Paper", "8", "Linux")),
                directory);
    }

    private static RemoteEvent log(String message) {
        return RemoteEvent.of("log", Collections.<String, Object>singletonMap("message", message));
    }

    private static String accepted(byte[] batch) {
        List<String> eventIds = eventIds(batch);
        StringBuilder results = new StringBuilder();
        for (int index = 0; index < eventIds.size(); index++) {
            if (index > 0) results.append(',');
            results.append("{\"index\":").append(index)
                    .append(",\"event_id\":\"").append(eventIds.get(index))
                    .append("\",\"status\":\"accepted\"}");
        }
        return "{\"results\":[" + results + "],\"accepted\":" + eventIds.size()
                + ",\"duplicate\":0,\"rejected\":0}";
    }

    private static String successfulReceiptWithError(byte[] batch) {
        List<String> eventIds = eventIds(batch);
        StringBuilder results = new StringBuilder();
        for (int index = 0; index < eventIds.size(); index++) {
            if (index > 0) results.append(',');
            results.append("{\"index\":").append(index)
                    .append(",\"event_id\":\"").append(eventIds.get(index))
                    .append("\",\"status\":\"accepted\"");
            if (index == 0) results.append(",\"error\":\"must-not-exist\"");
            results.append('}');
        }
        return "{\"results\":[" + results + "],\"accepted\":" + eventIds.size()
                + ",\"duplicate\":0,\"rejected\":0}";
    }

    private static String mixedReceipt(byte[] batch) {
        List<String> eventIds = eventIds(batch);
        StringBuilder results = new StringBuilder();
        int accepted = 0;
        int duplicate = 0;
        int rejected = 0;
        for (int index = 0; index < eventIds.size(); index++) {
            if (index > 0) results.append(',');
            results.append("{\"index\":").append(index)
                    .append(",\"event_id\":\"").append(eventIds.get(index)).append("\"");
            if (index == 1) {
                duplicate++;
                results.append(",\"status\":\"duplicate\"}");
            } else if (index == 2) {
                rejected++;
                results.append(",\"status\":\"rejected\",\"error\":\"invalid_event\"}");
            } else {
                accepted++;
                results.append(",\"status\":\"accepted\"}");
            }
        }
        return "{\"results\":[" + results + "],\"accepted\":" + accepted
                + ",\"duplicate\":" + duplicate + ",\"rejected\":" + rejected + "}";
    }

    @SuppressWarnings("unchecked")
    private static List<String> eventIds(byte[] batch) {
        Map<String, Object> decoded = (Map<String, Object>) StrictJsonParser.parse(
                new String(batch, StandardCharsets.UTF_8));
        List<Object> events = (List<Object>) decoded.get("events");
        List<String> result = new ArrayList<String>(events.size());
        for (Object encoded : events) {
            result.add((String) ((Map<String, Object>) encoded).get("event_id"));
        }
        return result;
    }

    private static String settingsResponse() {
        return "{\"schema_version\":1,\"key_status\":\"active\","
                + "\"environment\":\"production\",\"accepting_events\":true,"
                + "\"policy\":{\"paused\":false,\"exceptions\":true,\"logs\":true,"
                + "\"manual_incidents\":true,\"minimum_level\":\"info\","
                + "\"sample_rate\":100},\"limits\":{"
                + "\"max_compressed_bytes\":262144,\"max_decompressed_bytes\":1048576,"
                + "\"max_batch_events\":100,\"max_event_bytes\":262144,"
                + "\"max_message_bytes\":200000,\"max_attributes\":32,"
                + "\"max_installation_id_bytes\":128,\"max_event_id_bytes\":128,"
                + "\"max_attribute_key_bytes\":64,\"max_attribute_value_bytes\":1024,"
                + "\"max_environment_field_bytes\":128,\"key_events_per_minute\":1000,"
                + "\"ip_events_per_minute\":2000,"
                + "\"installation_events_per_minute\":1000,"
                + "\"asn_events_per_minute\":5000,"
                + "\"product_spike_events_per_minute\":10000},\"retention\":{"
                + "\"logs_days\":7,\"incidents_days\":30,\"aggregates_days\":90}}";
    }

    private static String tightSettingsResponse() {
        return settingsResponse()
                .replace("\"max_event_bytes\":262144", "\"max_event_bytes\":1024")
                .replace("\"max_message_bytes\":200000", "\"max_message_bytes\":512");
    }

    private static String repeat(char value, int length) {
        StringBuilder result = new StringBuilder(length);
        for (int index = 0; index < length; index++) result.append(value);
        return result.toString();
    }

    private static void await(Check check, long timeoutMillis) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (!check.done() && System.nanoTime() < deadline) Thread.sleep(10L);
        assertTrue(check.done());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting for test latch");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for test latch", interrupted);
        }
    }

    private interface Check { boolean done() throws Exception; }

    private static final class AcceptingTransport implements RpkTransport {
        @Override public String settings() { return settingsResponse(); }
        @Override public String sendBatch(byte[] json) { return accepted(json); }
    }
}
