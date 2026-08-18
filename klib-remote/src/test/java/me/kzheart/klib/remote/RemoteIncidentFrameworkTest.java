package me.kzheart.klib.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;
import me.kzheart.klib.diagnostic.DiagnosticSource;
import me.kzheart.klib.scope.Disposable;
import org.junit.jupiter.api.Test;

class RemoteIncidentFrameworkTest {
    @Test
    void throwableSnapshotKeepsCauseSuppressedAndFrames() {
        IllegalArgumentException cause = new IllegalArgumentException("bad config");
        IllegalStateException failure = new IllegalStateException("failed", cause);
        failure.addSuppressed(new java.io.IOException("flush interrupted"));

        Map<String, Object> snapshot = ThrowableSnapshot.capture(
                failure, IncidentBudget.defaults()).toMap();

        assertEquals("java.lang.IllegalStateException", snapshot.get("type"));
        assertFalse(((List<?>) snapshot.get("stack")).isEmpty());
        assertEquals("java.lang.IllegalArgumentException",
                ((Map<?, ?>) snapshot.get("cause")).get("type"));
        assertEquals("java.io.IOException",
                ((Map<?, ?>) ((List<?>) snapshot.get("suppressed")).get(0)).get("type"));
    }

    @Test
    void operationContextPropagatesAcrossAsyncBoundaryAndCreatesChild() throws Exception {
        AtomicReference<RemoteOperation.Context> child = new AtomicReference<RemoteOperation.Context>();
        CountDownLatch completed = new CountDownLatch(1);
        try (RemoteOperation parent = RemoteOperation.start("player.join")
                .attribute("player_kind", "returning")) {
            Runnable propagated = RemoteOperation.wrapCurrent(() -> {
                try (RemoteOperation operation = RemoteOperation.start("trade.restore")) {
                    child.set(operation.context());
                } finally {
                    completed.countDown();
                }
            });
            new Thread(propagated).start();
            assertTrue(completed.await(5, TimeUnit.SECONDS));
            assertEquals(parent.context().id(), child.get().parentId());
            assertEquals(1, ((List<?>) child.get().toMap().get("ancestors")).size());
            assertEquals("returning", ((Map<?, ?>) parent.context().toMap()
                    .get("attributes")).get("player_kind"));
        }
    }

    @Test
    void operationDurationIsNullUntilClosedAndPropagatesToFinishedAncestors() {
        AtomicReference<RemoteOperation.Context> child =
                new AtomicReference<RemoteOperation.Context>();
        RemoteOperation parent = RemoteOperation.start("player.save");
        RemoteOperation.Context parentContext = parent.context();
        Runnable propagated = RemoteOperation.wrapCurrent(() -> {
            try (RemoteOperation operation = RemoteOperation.start("inventory.flush")) {
                child.set(operation.context());
                assertNull(operation.context().toMap().get("duration_ms"));
                Map<?, ?> ancestor = (Map<?, ?>) ((List<?>) operation.context().toMap()
                        .get("ancestors")).get(0);
                assertNotNull(ancestor.get("duration_ms"));
            }
        });

        assertNull(parentContext.toMap().get("duration_ms"));
        parent.close();
        assertTrue(((Number) parentContext.toMap().get("duration_ms")).longValue() >= 0L);

        propagated.run();
        assertNotNull(child.get());
        assertTrue(((Number) child.get().toMap().get("duration_ms")).longValue() >= 0L);

        RemoteOperation nestedParent = RemoteOperation.start("nested.parent");
        RemoteOperation nestedChild = RemoteOperation.start("nested.child");
        RemoteOperation.Context nestedChildContext = nestedChild.context();
        nestedChild.close();
        nestedParent.close();
        Map<?, ?> completedAncestor = (Map<?, ?>) ((List<?>) nestedChildContext.toMap()
                .get("ancestors")).get(0);
        assertNotNull(completedAncestor.get("duration_ms"));
    }

    @Test
    void operationContextPropagatesThroughExecutorAdapter() {
        AtomicReference<String> observed = new AtomicReference<String>();
        Executor direct = Runnable::run;
        try (RemoteOperation operation = RemoteOperation.start("storage.flush")) {
            Executor wrapped = command -> direct.execute(RemoteOperation.wrapCurrent(command));
            wrapped.execute(() -> observed.set(RemoteOperation.current().id()));
            assertEquals(operation.context().id(), observed.get());
        }
    }

    @Test
    void manualIncidentAndAutomaticIncidentUseIndependentPolicySwitches() {
        List<RemoteEvent> events = new ArrayList<RemoteEvent>();
        AtomicReference<RemotePolicy> policy = new AtomicReference<RemotePolicy>(
                RemotePolicy.builder().manualIncidents(true).build());
        RemoteLogger remote = RemoteLogger.builder("example.market", events::add)
                .policy(policy::get)
                .build();
        try {
            remote.captureIncident("automatic", new IllegalStateException("boom"));
            remote.captureManualIncident("manual", Collections.singletonMap("reason", "operator"));
            assertEquals(1, events.size());
            assertEquals("manual", events.get(0).toMap().get("message"));
            assertEquals("manual", events.get(0).toMap().get("source"));

            policy.set(RemotePolicy.builder().exceptions(true).build());
            remote.captureIncident("automatic", new IllegalStateException("boom"));
            remote.captureManualIncident("manual", Collections.emptyMap());
            assertEquals(2, events.size());
            assertEquals("automatic", events.get(1).toMap().get("message"));
            assertEquals("automatic", events.get(1).toMap().get("source"));
        } finally {
            remote.dispose();
        }
    }

    @Test
    void bridgeSwallowsPolicyAndSinkFailures() {
        RemoteLogger remote = RemoteLogger.builder("example.market", event -> {
            throw new IllegalStateException("sink failed");
        }).policy(() -> { throw new IllegalStateException("policy failed"); }).build();
        Logger plugin = Logger.getLogger("test.plugin.fail-safe");
        plugin.setUseParentHandlers(false);
        Disposable bridge = remote.bridge(plugin);
        try {
            plugin.log(Level.SEVERE, "must not throw", new IllegalStateException("boom"));
        } finally {
            bridge.dispose();
            remote.dispose();
        }
    }

    @Test
    void directLoggingAndIncidentCaptureAlsoIsolateSinkFailures() {
        RemoteLogger remote = RemoteLogger.builder("example.market", event -> {
            throw new IllegalStateException("sink failed");
        }).policy(() -> RemotePolicy.builder().logs(true).exceptions(true).build()).build();
        try {
            remote.info("business thread continues");
            remote.captureIncident("commit", new IllegalStateException("boom"));
            assertEquals(2L, remote.droppedEvents());
        } finally {
            remote.dispose();
        }
    }

    @Test
    void loggerBridgeOnlyAcceptsDedicatedLoggerAndPausedLogsStillFeedIncidentWindow() {
        List<RemoteEvent> events = new ArrayList<RemoteEvent>();
        RemotePolicy disabledLogs = RemotePolicy.builder()
                .exceptions(true)
                .logs(false)
                .build();
        RemoteLogger remote = RemoteLogger.builder("example.market", events::add)
                .policy(() -> disabledLogs)
                .build();
        Logger plugin = Logger.getLogger("test.plugin.dedicated");
        plugin.setUseParentHandlers(false);
        Logger other = Logger.getLogger("test.plugin.other");
        other.setUseParentHandlers(false);
        Disposable bridge = remote.bridge(plugin);
        try {
            plugin.log(Level.WARNING, "dedicated message");
            other.log(Level.WARNING, "other message");
            assertTrue(events.isEmpty(), "日志流关闭时不发送独立日志事件");

            Incident incident = remote.captureIncident("reload-stalls",
                    new IllegalStateException("boom"));
            Map<?, ?> payload = (Map<?, ?>) incident.toMap().get("payload");
            List<?> window = payload.get("log_window") instanceof List<?>
                    ? (List<?>) payload.get("log_window")
                    : Collections.emptyList();
            assertEquals(1, window.size());
            assertTrue(String.valueOf(window.get(0)).contains("dedicated message"));
        } finally {
            bridge.dispose();
            remote.dispose();
        }
    }

    @Test
    void contributorFailureAndTimeoutAreIsolated() {
        List<RemoteEvent> events = new ArrayList<RemoteEvent>();
        IncidentBudget budget = IncidentBudget.builder()
                .contributorTimeout(Duration.ofMillis(30))
                .maxContributors(3)
                .build();
        RemoteLogger remote = RemoteLogger.builder("example.market", events::add)
                .policy(() -> RemotePolicy.builder().exceptions(true).build())
                .budget(budget)
                .contributor(new DiagnosticContributor() {
                    @Override public String name() { return "failing"; }
                    @Override public Map<String, ?> contribute(Context context) {
                        throw new IllegalStateException("broken");
                    }
                })
                .contributor(new DiagnosticContributor() {
                    @Override public String name() { return "blocking"; }
                    @Override public Map<String, ?> contribute(Context context) throws Exception {
                        Thread.sleep(5000L);
                        return Collections.emptyMap();
                    }
                })
                .build();
        try {
            Incident incident = remote.captureIncident("commit",
                    new IllegalStateException("boom"));
            Object raw = ((Map<?, ?>) incident.toMap().get("payload")).get("contributors");
            assertTrue(raw instanceof List<?>);
            List<?> contributors = (List<?>) raw;
            assertEquals(2, contributors.size());
            assertEquals("error", ((Map<?, ?>) contributors.get(0)).get("status"));
            assertEquals("timeout", ((Map<?, ?>) contributors.get(1)).get("status"));
            assertNotNull(events.get(0));
        } finally {
            remote.dispose();
        }
    }

    @Test
    void contributorTimeoutIsOneGlobalIncidentBudget() {
        IncidentBudget budget = IncidentBudget.builder()
                .contributorTimeout(Duration.ofMillis(100))
                .maxContributors(4)
                .build();
        RemoteLogger.Builder builder = RemoteLogger.builder("example.market", event -> { })
                .policy(() -> RemotePolicy.builder().exceptions(true).build())
                .budget(budget);
        for (int index = 0; index < 4; index++) {
            final int contributor = index;
            builder.contributor(new DiagnosticContributor() {
                @Override public String name() { return "blocking-" + contributor; }
                @Override public Map<String, ?> contribute(Context context) throws Exception {
                    Thread.sleep(5000L);
                    return Collections.emptyMap();
                }
            });
        }
        RemoteLogger remote = builder.build();
        long started = System.nanoTime();
        try {
            remote.captureIncident("bounded", new IllegalStateException("boom"));
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue(elapsedMillis < 300L,
                    "Contributor 超时必须是一次 Incident 的总预算，而不是逐项叠加: "
                            + elapsedMillis + "ms");
        } finally {
            remote.dispose();
        }
    }

    @Test
    void contributorNameAndValueConversionShareTheGlobalDeadline() {
        IncidentBudget budget = IncidentBudget.builder()
                .contributorTimeout(Duration.ofMillis(30))
                .maxContributors(2)
                .build();
        RemoteLogger remote = RemoteLogger.builder("example.market", event -> { })
                .budget(budget)
                .contributor(new DiagnosticContributor() {
                    @Override public String name() {
                        sleepIgnoringInterrupts(250L);
                        return "late-name";
                    }
                    @Override public Map<String, ?> contribute(Context context) {
                        return Collections.emptyMap();
                    }
                })
                .contributor(new DiagnosticContributor() {
                    @Override public String name() { return "late-value"; }
                    @Override public Map<String, ?> contribute(Context context) {
                        return Collections.<String, Object>singletonMap("value", new Object() {
                            @Override public String toString() {
                                sleepIgnoringInterrupts(250L);
                                return "late";
                            }
                        });
                    }
                })
                .build();
        long started = System.nanoTime();
        try {
            Incident incident = remote.captureIncident("bounded",
                    new IllegalStateException("boom"));
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue(elapsedMillis < 200L,
                    "name()/toString() 必须受统一 Contributor deadline 约束: "
                            + elapsedMillis + "ms");
            List<?> contributors = (List<?>) ((Map<?, ?>) incident.toMap().get("payload"))
                    .get("contributors");
            assertEquals("timeout", ((Map<?, ?>) contributors.get(0)).get("status"));
            assertEquals("timeout", ((Map<?, ?>) contributors.get(1)).get("status"));
        } finally {
            remote.dispose();
        }
    }

    @Test
    void throwableSnapshotHasGlobalIdentityNodeBudget() {
        Throwable root = throwableTree(8);
        IncidentBudget budget = IncidentBudget.builder()
                .throwableDepth(16)
                .suppressedPerThrowable(2)
                .throwableNodes(12)
                .build();

        Map<String, Object> snapshot = ThrowableSnapshot.capture(root, budget).toMap();

        assertTrue(countThrowableNodes(snapshot) <= 12);
        assertTrue(String.valueOf(snapshot).contains("nodes"));
    }

    @Test
    void eventAndBreadcrumbRejectCyclesDepthAndContainerEntryOverflow() {
        Map<String, Object> cycle = new LinkedHashMap<String, Object>();
        cycle.put("self", cycle);
        assertThrows(IllegalArgumentException.class, () -> RemoteEvent.of("log", cycle));
        assertThrows(IllegalArgumentException.class,
                () -> new Breadcrumb("test", "cycle", cycle));

        Map<String, Object> root = new LinkedHashMap<String, Object>();
        Map<String, Object> cursor = root;
        for (int index = 0; index < 40; index++) {
            Map<String, Object> next = new LinkedHashMap<String, Object>();
            cursor.put("next", next);
            cursor = next;
        }
        assertThrows(IllegalArgumentException.class, () -> RemoteEvent.of("log", root));

        Map<String, Object> wide = new LinkedHashMap<String, Object>();
        for (int index = 0; index < 300; index++) wide.put("key-" + index, index);
        assertThrows(IllegalArgumentException.class, () -> RemoteEvent.of("log", wide));
    }

    @Test
    void eventSnapshotRejectsUnsupportedValuesWithoutCallingToString() {
        AtomicBoolean called = new AtomicBoolean();
        Object unsupported = new Object() {
            @Override public String toString() {
                called.set(true);
                throw new AssertionError("toString must not run");
            }
        };

        assertThrows(IllegalArgumentException.class, () -> RemoteEvent.of("log",
                Collections.<String, Object>singletonMap("unsupported", unsupported)));
        assertFalse(called.get());

        Map<Object, Object> nested = new LinkedHashMap<Object, Object>();
        nested.put(unsupported, "value");
        assertThrows(IllegalArgumentException.class, () -> RemoteEvent.of("log",
                Collections.<String, Object>singletonMap("nested", nested)));
        assertFalse(called.get());
    }

    @Test
    void eventSnapshotAcceptsExplicitArraysAndRejectsNonFiniteNumbers() {
        Map<String, Object> fields = new LinkedHashMap<String, Object>();
        fields.put("numbers", new int[] {1, 2});
        fields.put("strings", new String[] {"a", "b"});
        RemoteEvent event = RemoteEvent.of("log", fields);

        assertEquals(java.util.Arrays.asList(1, 2), event.toMap().get("numbers"));
        assertEquals(java.util.Arrays.asList("a", "b"), event.toMap().get("strings"));
        assertThrows(IllegalArgumentException.class, () -> RemoteEvent.of("log",
                Collections.<String, Object>singletonMap("number", Double.NaN)));
        assertThrows(IllegalArgumentException.class, () -> RemoteEvent.of("log",
                Collections.<String, Object>singletonMap("number", Float.POSITIVE_INFINITY)));
    }

    @Test
    void klibDiagnosticSourceIsAdaptedWithoutModuleDependency() {
        DiagnosticSource source = new DiagnosticSource() {
            @Override public String diagnosticName() { return "config"; }
            @Override public Map<String, ?> diagnosticSnapshot() {
                return Collections.singletonMap("last_reload", "ok");
            }
        };
        KlibDiagnosticContributor contributor = new KlibDiagnosticContributor(source);

        assertEquals("config", contributor.name());
        assertEquals("ok", contributor.contribute(null).get("last_reload"));
    }

    private static Throwable throwableTree(int depth) {
        Throwable value = new IllegalStateException("node-" + depth);
        if (depth > 0) {
            value.addSuppressed(throwableTree(depth - 1));
            value.addSuppressed(throwableTree(depth - 1));
        }
        return value;
    }

    private static int countThrowableNodes(Object value) {
        if (value instanceof Map<?, ?>) {
            int count = ((Map<?, ?>) value).containsKey("type") ? 1 : 0;
            for (Object nested : ((Map<?, ?>) value).values()) {
                count += countThrowableNodes(nested);
            }
            return count;
        }
        if (value instanceof Iterable<?>) {
            int count = 0;
            for (Object nested : (Iterable<?>) value) count += countThrowableNodes(nested);
            return count;
        }
        return 0;
    }

    private static void sleepIgnoringInterrupts(long millis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (System.nanoTime() < deadline) {
            try {
                long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                Thread.sleep(Math.max(1L, remaining));
            } catch (InterruptedException ignored) {
                // 模拟不遵守中断约定的第三方 Contributor。
            }
        }
    }
}
