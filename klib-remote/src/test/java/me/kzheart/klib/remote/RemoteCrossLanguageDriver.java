package me.kzheart.klib.remote;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/** 由 Go 端到端测试进程调用的真实 Java Remote 客户端驱动。 */
public final class RemoteCrossLanguageDriver {
    private static final String INSTALLATION_ID = "installation-java-e2e";

    private RemoteCrossLanguageDriver() { }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 4) {
            throw new IllegalArgumentException("expected mode, endpoint, public key and queue path");
        }
        if ("online".equals(arguments[0])) {
            runOnline(arguments[1], arguments[2], Paths.get(arguments[3]));
        } else if ("offline".equals(arguments[0])) {
            runOffline(arguments[1], arguments[2], Paths.get(arguments[3]));
        } else if ("drain".equals(arguments[0])) {
            runDrain(arguments[1], arguments[2], Paths.get(arguments[3]));
        } else {
            throw new IllegalArgumentException("unsupported mode: " + arguments[0]);
        }
    }

    private static void runOnline(String endpoint, String publicKey, Path queue) throws Exception {
        RemoteCapabilities capabilities = capabilities();
        RemoteClient client = RemoteClient.insecureLoopback(endpoint, publicKey, capabilities,
                () -> INSTALLATION_ID, environment());
        RemotePolicy policy = client.refreshPolicy();
        require(policy.logs() && policy.exceptions() && policy.sampleRate() == 100,
                "unexpected effective policy");

        RemoteDelivery delivery = RemoteDelivery.builder(client, queue)
                .initialBackoff(Duration.ofMillis(20L))
                .maxBackoff(Duration.ofMillis(100L))
                .settingsRefreshInterval(Duration.ofSeconds(1L))
                .build();
        List<RemoteEvent> captured = Collections.synchronizedList(
                new ArrayList<RemoteEvent>());
        List<CompletionStage<Boolean>> persisted = Collections.synchronizedList(
                new ArrayList<CompletionStage<Boolean>>());
        RemoteLogger logger = RemoteLogger.builder("e2e.remote.logger", event -> {
            captured.add(event);
            persisted.add(delivery.submit(event));
        }).policy(client::policy).build();
        try {
            Throwable failure = automaticFailure();
            RemoteOperation operation = RemoteOperation.start("checkout.complete")
                    .phase("persist")
                    .attribute("tenant", "alpha");
            try {
                RemoteLogContext context = RemoteLogContext.builder()
                        .context("request_id", "request-fixed-001")
                        .mdc("trace_id", "trace-fixed-001")
                        .tag("cross-language")
                        .build();
                logger.log(Level.INFO, "remote-e2e-log", context);
                logger.captureIncident("remote-e2e-incident", failure);
                logger.captureIncident("remote-e2e-incident", failure);
            } finally {
                operation.close();
            }
            require(captured.size() == 3, "logger did not emit the expected events");
            for (CompletionStage<Boolean> stage : persisted) {
                require(Boolean.TRUE.equals(stage.toCompletableFuture().get(5L, TimeUnit.SECONDS)),
                        "event was not persisted to the delivery queue");
            }
            awaitDrained(delivery, 10_000L);

            Map<String, Object> receiptFields = new LinkedHashMap<String, Object>();
            receiptFields.put("level", "info");
            receiptFields.put("logger", "e2e.receipt.logger");
            receiptFields.put("message", "remote-e2e-receipt");
            RemoteEvent receiptEvent = RemoteEvent.of("log", receiptFields);
            RemoteBatchResult accepted = client.sendBatch(
                    Collections.<RemoteEvent>singletonList(receiptEvent));
            require(accepted.accepted() == 1 && accepted.duplicate() == 0
                            && accepted.rejected() == 0,
                    "server did not return an accepted receipt");
            RemoteBatchResult duplicate = client.sendBatch(
                    Collections.<RemoteEvent>singletonList(receiptEvent));
            require(duplicate.accepted() == 0 && duplicate.duplicate() == 1
                            && duplicate.rejected() == 0,
                    "server did not return a duplicate receipt");
            require(delivery.droppedEvents() == 0L, "delivery dropped an event");
            System.out.println("REMOTE_E2E_ONLINE_OK");
        } finally {
            logger.dispose();
            delivery.dispose();
        }
    }

    private static void runOffline(String endpoint, String publicKey, Path queue) throws Exception {
        HttpRpkTransport transport = new HttpRpkTransport(
                endpoint, publicKey, true, 200, 200);
        RemoteClient client = new RemoteClient(transport, capabilities(),
                () -> INSTALLATION_ID, environment());
        RemoteDelivery delivery = RemoteDelivery.builder(client, queue)
                .initialBackoff(Duration.ofMillis(20L))
                .maxBackoff(Duration.ofMillis(50L))
                .settingsRefreshInterval(Duration.ofMillis(50L))
                .build();
        RemoteLogger logger = RemoteLogger.builder("e2e.recovery.logger", delivery)
                .policy(() -> RemotePolicy.builder()
                        .logs(true)
                        .exceptions(true)
                        .sampleRate(100)
                        .build())
                .build();
        try {
            logger.info("recovery-log");
            logger.captureIncident("recovery-incident", automaticFailure());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
            while (delivery.queuedEvents() < 2L && System.nanoTime() < deadline) {
                Thread.sleep(10L);
            }
            require(delivery.queuedEvents() == 2L,
                    "unavailable Remote did not retain events in the queue");
            Thread.sleep(500L);
            require(delivery.isActive(), "transient unavailability closed delivery");
            require(delivery.queuedEvents() == 2L, "unavailable Remote removed queued events");
            System.out.println("REMOTE_E2E_OFFLINE_OK");
        } finally {
            logger.dispose();
            delivery.dispose();
        }
    }

    private static void runDrain(String endpoint, String publicKey, Path queue) throws Exception {
        RemoteClient client = RemoteClient.insecureLoopback(endpoint, publicKey, capabilities(),
                () -> INSTALLATION_ID, environment());
        RemoteDelivery delivery = RemoteDelivery.builder(client, queue)
                .initialBackoff(Duration.ofMillis(20L))
                .maxBackoff(Duration.ofMillis(100L))
                .settingsRefreshInterval(Duration.ofMillis(50L))
                .build();
        try {
            awaitDrained(delivery, 10_000L);
            require(delivery.droppedEvents() == 0L, "recovery dropped an event");
            System.out.println("REMOTE_E2E_DRAIN_OK");
        } finally {
            delivery.dispose();
        }
    }

    private static RemoteCapabilities capabilities() {
        return RemoteCapabilities.builder().logs(true).exceptions(true).build();
    }

    private static RemoteEnvironment environment() {
        return new RemoteEnvironment("9.8.7-e2e", "Paper 1.21.4", "8", "Linux-e2e");
    }

    private static Throwable automaticFailure() {
        return new IllegalStateException("deterministic-e2e-failure");
    }

    private static void awaitDrained(RemoteDelivery delivery, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (delivery.queuedEvents() != 0L && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
        require(delivery.queuedEvents() == 0L, "delivery queue did not drain");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
