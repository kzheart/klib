package me.kzheart.klib.remote;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import me.kzheart.klib.scope.Disposable;

/** 当前插件专属的结构化日志与 Incident 入口。 */
public final class RemoteLogger implements Disposable {
    private final String name;
    private final Consumer<? super RemoteEvent> sink;
    private final Supplier<RemotePolicy> policy;
    private final IncidentBudget budget;
    private final List<DiagnosticContributor> contributors;
    private final ThreadPoolExecutor contributorExecutor;
    private final BoundedFacts<RemoteEvent> logWindow;
    private final BoundedFacts<Breadcrumb> breadcrumbs;
    private final AtomicLong droppedEvents = new AtomicLong();
    private volatile boolean closed;

    private RemoteLogger(Builder builder) {
        name = builder.name;
        sink = builder.sink;
        policy = builder.policy;
        budget = builder.budget;
        contributors = Collections.unmodifiableList(
                new ArrayList<DiagnosticContributor>(builder.contributors));
        logWindow = new BoundedFacts<RemoteEvent>(budget.logEntries(), budget.logBytes());
        breadcrumbs = new BoundedFacts<Breadcrumb>(budget.breadcrumbs(), budget.breadcrumbBytes());
        int contributorWorkers = Math.max(1, Math.min(4, budget.maxContributors()));
        contributorExecutor = new ThreadPoolExecutor(
                contributorWorkers,
                contributorWorkers,
                30L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(Math.max(1, budget.maxContributors())),
                task -> {
                    Thread thread = new Thread(task, "klib-remote-contributor");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
        contributorExecutor.allowCoreThreadTimeOut(true);
    }

    public static Builder builder(String loggerName, Consumer<? super RemoteEvent> sink) {
        return new Builder(loggerName, sink);
    }

    public void info(String message) { log(Level.INFO, message, Collections.<String, Object>emptyMap()); }
    public void warn(String message) { log(Level.WARNING, message, Collections.<String, Object>emptyMap()); }
    public void error(String message) { log(Level.SEVERE, message, Collections.<String, Object>emptyMap()); }

    public void log(Level level, String message, Map<String, ?> context) {
        log(level, message, RemoteLogContext.builder().context(context).build());
    }

    public void log(Level level, String message, RemoteLogContext logContext) {
        Objects.requireNonNull(level, "level");
        String text = Objects.requireNonNull(message, "message");
        Objects.requireNonNull(logContext, "logContext");
        Map<String, Object> fields = new LinkedHashMap<String, Object>();
        fields.put("event_id", EventIds.next());
        fields.put("type", "log");
        fields.put("occurred_at", Instant.now().toString());
        fields.put("level", wireLevel(level));
        fields.put("logger", name);
        fields.put("message", text);
        fields.put("attributes", logContext.context());
        fields.put("tags", tagMap(logContext.tags()));
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("context", logContext.context());
        payload.put("mdc", logContext.mdc());
        payload.put("tags", logContext.tags());
        RemoteOperation.Context operation = RemoteOperation.current();
        if (operation != null) {
            fields.put("operation_id", operation.id());
            payload.put("operation", operation.toMap());
        }
        fields.put("payload", payload);
        RemoteEvent event = new RemoteEvent(fields);
        logWindow.add(event, DiagnosticJson.write(event.toMap()));
        RemotePolicy current = safePolicy();
        if (!closed && current != null && current.accepts(level)
                && RemoteSampling.accepts(String.valueOf(fields.get("event_id")),
                        current.sampleRate())) {
            emit(event);
        }
    }

    /** 只桥接指定 Logger 自身的记录，不监听根 Logger、子 Logger 或其他插件。 */
    public Disposable bridge(final Logger source) {
        Objects.requireNonNull(source, "source");
        final String sourceName = source.getName();
        final Handler handler = new Handler() {
            @Override public void publish(LogRecord record) {
                try {
                    if (closed || !isLoggable(record)
                            || !Objects.equals(sourceName, record.getLoggerName())) return;
                    Map<String, Object> context = new LinkedHashMap<String, Object>();
                    context.put("thread_id", record.getThreadID());
                    if (record.getThrown() != null) {
                        context.put("throwable_type", record.getThrown().getClass().getName());
                    }
                    log(record.getLevel(), String.valueOf(record.getMessage()), context);
                    if (record.getThrown() != null && safePolicy().exceptions()) {
                        captureIncident("logger:" + sourceName, record.getThrown());
                    }
                } catch (Throwable ignored) {
                    // Logger Handler 绝不能把 Remote 的策略、序列化或 sink 故障抛回插件。
                }
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        source.addHandler(handler);
        return new Disposable() {
            @Override public void dispose() { source.removeHandler(handler); }
        };
    }

    public void breadcrumb(String category, String message) {
        breadcrumb(category, message, Collections.<String, Object>emptyMap());
    }

    public void breadcrumb(String category, String message, Map<String, ?> context) {
        Breadcrumb breadcrumb = new Breadcrumb(category, message, context);
        breadcrumbs.add(breadcrumb, DiagnosticJson.write(breadcrumb.toMap()));
    }

    public Incident captureIncident(String name, Throwable error) {
        Objects.requireNonNull(error, "error");
        Map<String, Object> fields = new LinkedHashMap<String, Object>();
        fields.put("event_id", EventIds.next());
        fields.put("type", "incident");
        fields.put("occurred_at", Instant.now().toString());
        fields.put("message", Texts.requireText(name, "name"));
        fields.put("fingerprint", IssueFingerprint.of(error));
        fields.put("source", "automatic");
        RemoteOperation.Context operation = RemoteOperation.current();
        if (operation != null) fields.put("operation_id", operation.id());
        Map<String, Object> payload = incidentPayload(error);
        fields.put("payload", payload);
        Incident incident = new Incident(fields);
        RemotePolicy current = safePolicy();
        if (!closed && current.exceptions()) emit(incident);
        return incident;
    }

    /** 创建开发者显式触发、没有 Throwable 的手动 Incident。 */
    public Incident captureManualIncident(String name, Map<String, ?> attributes) {
        Map<String, Object> fields = new LinkedHashMap<String, Object>();
        fields.put("event_id", EventIds.next());
        fields.put("type", "incident");
        fields.put("occurred_at", Instant.now().toString());
        fields.put("message", Texts.requireText(name, "name"));
        fields.put("fingerprint", "manual:" + name);
        fields.put("source", "manual");
        RemoteOperation.Context operation = RemoteOperation.current();
        if (operation != null) fields.put("operation_id", operation.id());
        fields.put("attributes", stringAttributes(attributes));
        Map<String, Object> payload = incidentPayload(null);
        payload.put("manual", Boolean.TRUE);
        fields.put("payload", payload);
        Incident incident = new Incident(fields);
        RemotePolicy current = safePolicy();
        if (!closed && current.manualIncidents()) emit(incident);
        return incident;
    }

    /** sink 拒绝或抛错而被隔离的事件数量。 */
    public long droppedEvents() { return droppedEvents.get(); }

    private Map<String, Object> incidentPayload(Throwable error) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        if (error != null) payload.put("throwable", ThrowableSnapshot.capture(error, budget).toMap());
        RemoteOperation.Context operation = RemoteOperation.current();
        if (operation != null) payload.put("operation", operation.toMap());
        payload.put("log_window", maps(logWindow.snapshot()));
        payload.put("breadcrumbs", breadcrumbMaps(breadcrumbs.snapshot()));
        payload.put("contributors", collectContributors(error, operation));
        return payload;
    }

    @Override public void dispose() {
        closed = true;
        contributorExecutor.shutdownNow();
    }

    private RemotePolicy safePolicy() {
        try {
            RemotePolicy current = policy.get();
            return current == null ? RemotePolicy.builder().build() : current;
        } catch (Throwable ignored) {
            return RemotePolicy.builder().build();
        }
    }

    private void emit(RemoteEvent event) {
        try {
            sink.accept(event);
        } catch (Throwable ignored) {
            droppedEvents.incrementAndGet();
        }
    }

    private static Map<String, String> stringAttributes(Map<String, ?> values) {
        if (values == null) return Collections.emptyMap();
        return RemoteLogContext.builder().context(values).build().context();
    }

    private static Map<String, String> tagMap(List<String> tags) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (String tag : tags) result.put(tag, "true");
        return result;
    }

    private List<Map<String, Object>> collectContributors(
            Throwable error, RemoteOperation.Context operation) {
        List<Map<String, Object>> results = new ArrayList<Map<String, Object>>();
        List<ContributorTask> tasks = new ArrayList<ContributorTask>();
        int limit = Math.min(contributors.size(), budget.maxContributors());
        DiagnosticContributor.Context context = new DiagnosticContributor.Context(error, operation);
        long deadline = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(budget.contributorTimeoutMillis());
        for (int index = 0; index < limit; index++) {
            DiagnosticContributor contributor = contributors.get(index);
            Future<Map<String, Object>> future;
            try {
                final int contributorIndex = index;
                future = contributorExecutor.submit(
                        () -> collectContributor(contributor, context, contributorIndex));
            } catch (RuntimeException rejected) {
                Map<String, Object> result = new LinkedHashMap<String, Object>();
                result.put("name", "contributor-" + index);
                result.put("status", "rejected");
                future = CompletableFuture.completedFuture(result);
            }
            tasks.add(new ContributorTask(index, future));
        }

        for (ContributorTask task : tasks) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) {
                task.future.cancel(true);
                results.add(contributorFailure(task.index, "timeout", null));
                continue;
            }
            try {
                results.add(task.future.get(remaining, TimeUnit.NANOSECONDS));
            } catch (TimeoutException timeout) {
                task.future.cancel(true);
                results.add(contributorFailure(task.index, "timeout", null));
            } catch (Exception failure) {
                Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                results.add(contributorFailure(task.index, "error", cause));
            }
        }
        return results;
    }

    private Map<String, Object> collectContributor(DiagnosticContributor contributor,
            DiagnosticContributor.Context context, int index) throws Exception {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        String contributorName;
        try {
            contributorName = Texts.requireText(contributor.name(), "contributor name");
        } catch (RuntimeException failure) {
            contributorName = "invalid-contributor-" + index;
        }
        result.put("name", contributorName);
        try {
            result.put("data", boundedMap(contributor.contribute(context)));
            result.put("status", "ok");
        } catch (Throwable failure) {
            result.put("status", "error");
            result.put("error_type", failure.getClass().getName());
        }
        return result;
    }

    private static Map<String, Object> contributorFailure(
            int index, String status, Throwable failure) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("name", "contributor-" + index);
        result.put("status", status);
        if (failure != null) result.put("error_type", failure.getClass().getName());
        return result;
    }

    private static final class ContributorTask {
        private final int index;
        private final Future<Map<String, Object>> future;

        private ContributorTask(int index, Future<Map<String, Object>> future) {
            this.index = index;
            this.future = future;
        }
    }

    private Map<String, Object> boundedMap(Map<String, ?> values) {
        if (values == null) return Collections.emptyMap();
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        int bytes = 0;
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            if (result.size() >= budget.contributorEntries()) break;
            String key = String.valueOf(entry.getKey());
            String value = String.valueOf(entry.getValue());
            int entryBytes = key.getBytes(StandardCharsets.UTF_8).length
                    + value.getBytes(StandardCharsets.UTF_8).length;
            if (entryBytes > budget.contributorBytes()) continue;
            if (bytes + entryBytes > budget.contributorBytes()) break;
            result.put(key, value);
            bytes += entryBytes;
        }
        return result;
    }

    private static List<Map<String, Object>> maps(List<RemoteEvent> events) {
        List<Map<String, Object>> values = new ArrayList<Map<String, Object>>();
        for (RemoteEvent event : events) values.add(event.toMap());
        return values;
    }

    private static List<Map<String, Object>> breadcrumbMaps(List<Breadcrumb> events) {
        List<Map<String, Object>> values = new ArrayList<Map<String, Object>>();
        for (Breadcrumb event : events) values.add(event.toMap());
        return values;
    }

    private static String wireLevel(Level level) {
        int value = level.intValue();
        if (value >= Level.SEVERE.intValue()) return "error";
        if (value >= Level.WARNING.intValue()) return "warn";
        if (value >= Level.INFO.intValue()) return "info";
        if (value >= Level.FINE.intValue()) return "debug";
        return "trace";
    }

    private static final class BoundedFacts<T> {
        private final int maxEntries;
        private final int maxBytes;
        private final Deque<Entry<T>> entries = new ArrayDeque<Entry<T>>();
        private int bytes;

        private BoundedFacts(int maxEntries, int maxBytes) {
            this.maxEntries = maxEntries;
            this.maxBytes = maxBytes;
        }

        synchronized void add(T value, String encoded) {
            int size = encoded.getBytes(StandardCharsets.UTF_8).length;
            if (size > maxBytes) return;
            while (!entries.isEmpty()
                    && (entries.size() >= maxEntries || bytes + size > maxBytes)) {
                bytes -= entries.removeFirst().bytes;
            }
            entries.addLast(new Entry<T>(value, size));
            bytes += size;
        }

        synchronized List<T> snapshot() {
            List<T> result = new ArrayList<T>();
            for (Entry<T> entry : entries) result.add(entry.value);
            return result;
        }
    }

    private static final class Entry<T> {
        private final T value;
        private final int bytes;
        private Entry(T value, int bytes) { this.value = value; this.bytes = bytes; }
    }

    public static final class Builder {
        private final String name;
        private final Consumer<? super RemoteEvent> sink;
        private Supplier<RemotePolicy> policy = () -> RemotePolicy.builder().build();
        private IncidentBudget budget = IncidentBudget.defaults();
        private final List<DiagnosticContributor> contributors =
                new ArrayList<DiagnosticContributor>();

        private Builder(String name, Consumer<? super RemoteEvent> sink) {
            this.name = Texts.requireText(name, "loggerName");
            this.sink = Objects.requireNonNull(sink, "sink");
        }

        public Builder policy(Supplier<RemotePolicy> value) {
            policy = Objects.requireNonNull(value, "policy"); return this;
        }
        public Builder budget(IncidentBudget value) {
            budget = Objects.requireNonNull(value, "budget"); return this;
        }
        public Builder contributor(DiagnosticContributor value) {
            contributors.add(Objects.requireNonNull(value, "contributor")); return this;
        }
        public RemoteLogger build() { return new RemoteLogger(this); }
    }
}
