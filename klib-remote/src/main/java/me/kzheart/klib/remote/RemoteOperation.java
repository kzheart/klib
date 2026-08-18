package me.kzheart.klib.remote;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** 显式 operation 作用域；通过包装 Runnable/Callable 跨异步边界传播。 */
public final class RemoteOperation implements AutoCloseable {
    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<Context>();

    private final Context previous;
    private final boolean owner;
    private Context context;
    private boolean closed;

    private RemoteOperation(String name, Context parent) {
        previous = CURRENT.get();
        owner = true;
        context = new Context(
                "op_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                parent == null ? null : parent.id,
                Texts.requireText(name, "name"),
                null,
                "running",
                Instant.now().toString(),
                Collections.<String, String>emptyMap(),
                ancestry(parent),
                new Timing(System.nanoTime()));
        CURRENT.set(context);
    }

    private RemoteOperation(Context activate) {
        previous = CURRENT.get();
        owner = false;
        context = activate;
        CURRENT.set(activate);
    }

    public static RemoteOperation start(String name) {
        return new RemoteOperation(name, CURRENT.get());
    }

    public static Context current() { return CURRENT.get(); }

    public static Runnable wrapCurrent(final Runnable task) {
        Objects.requireNonNull(task, "task");
        final Context captured = CURRENT.get();
        return new Runnable() {
            @Override public void run() {
                if (captured == null) {
                    task.run();
                    return;
                }
                RemoteOperation activation = new RemoteOperation(captured);
                try {
                    task.run();
                } finally {
                    activation.close();
                }
            }
        };
    }

    public static <T> Callable<T> wrapCurrent(final Callable<T> task) {
        Objects.requireNonNull(task, "task");
        final Context captured = CURRENT.get();
        return new Callable<T>() {
            @Override public T call() throws Exception {
                if (captured == null) return task.call();
                RemoteOperation activation = new RemoteOperation(captured);
                try {
                    return task.call();
                } finally {
                    activation.close();
                }
            }
        };
    }

    /** 捕获当前 operation，供 Klib 异步调度器使用。 */
    public static <T> Supplier<T> wrapCurrent(final Supplier<T> task) {
        Objects.requireNonNull(task, "task");
        final Context captured = CURRENT.get();
        return new Supplier<T>() {
            @Override public T get() {
                if (captured == null) return task.get();
                RemoteOperation activation = new RemoteOperation(captured);
                try {
                    return task.get();
                } finally {
                    activation.close();
                }
            }
        };
    }

    public RemoteOperation phase(String value) {
        ensureOpen();
        context = context.withPhase(Texts.requireText(value, "phase"));
        CURRENT.set(context);
        return this;
    }

    public RemoteOperation outcome(String value) {
        ensureOpen();
        context = context.withOutcome(Texts.requireText(value, "outcome"));
        CURRENT.set(context);
        return this;
    }

    /** 添加一个有界字符串属性；最多 16 项，键 64 字节、值 1024 字节。 */
    public RemoteOperation attribute(String key, String value) {
        ensureOpen();
        context = context.withAttribute(key, value);
        CURRENT.set(context);
        return this;
    }

    public Context context() { return context; }

    @Override public void close() {
        if (closed) return;
        closed = true;
        if (owner) context.timing.finish();
        if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("operation is closed");
    }

    public static final class Context {
        private final String id;
        private final String parentId;
        private final String name;
        private final String phase;
        private final String outcome;
        private final String startedAt;
        private final Map<String, String> attributes;
        private final List<Context> ancestry;
        private final Timing timing;

        private Context(String id, String parentId, String name, String phase,
                String outcome, String startedAt, Map<String, String> attributes,
                List<Context> ancestry, Timing timing) {
            this.id = id;
            this.parentId = parentId;
            this.name = name;
            this.phase = phase;
            this.outcome = outcome;
            this.startedAt = startedAt;
            this.attributes = java.util.Collections.unmodifiableMap(
                    new LinkedHashMap<String, String>(attributes));
            this.ancestry = Collections.unmodifiableList(
                    new ArrayList<Context>(ancestry));
            this.timing = timing;
        }

        public String id() { return id; }
        public String parentId() { return parentId; }
        public String name() { return name; }
        public String phase() { return phase; }
        public String outcome() { return outcome; }

        private Context withPhase(String value) {
            return new Context(id, parentId, name, value, outcome, startedAt, attributes,
                    ancestry, timing);
        }

        private Context withOutcome(String value) {
            return new Context(id, parentId, name, phase, value, startedAt, attributes,
                    ancestry, timing);
        }

        private Context withAttribute(String key, String value) {
            String normalizedKey = Texts.requireText(key, "attribute key");
            String normalizedValue = Objects.requireNonNull(value, "attribute value");
            if (utf8(normalizedKey) > 64 || utf8(normalizedValue) > 1024) {
                throw new IllegalArgumentException("operation attribute exceeds byte limit");
            }
            Map<String, String> copy = new LinkedHashMap<String, String>(attributes);
            if (!copy.containsKey(normalizedKey) && copy.size() >= 16) {
                throw new IllegalStateException("operation attributes exceed limit");
            }
            copy.put(normalizedKey, normalizedValue);
            return new Context(id, parentId, name, phase, outcome, startedAt, copy, ancestry,
                    timing);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("id", id);
            result.put("parent_id", parentId);
            result.put("name", name);
            result.put("phase", phase);
            result.put("outcome", outcome);
            result.put("started_at", startedAt);
            result.put("duration_ms", timing.durationMillis());
            result.put("attributes", attributes);
            List<Map<String, Object>> ancestorMaps = new ArrayList<Map<String, Object>>();
            for (Context ancestor : ancestry) ancestorMaps.add(ancestor.nodeMap());
            result.put("ancestors", ancestorMaps);
            return result;
        }

        private Map<String, Object> nodeMap() {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("id", id);
            result.put("parent_id", parentId);
            result.put("name", name);
            result.put("phase", phase);
            result.put("outcome", outcome);
            result.put("started_at", startedAt);
            result.put("duration_ms", timing.durationMillis());
            result.put("attributes", attributes);
            return result;
        }

        private static int utf8(String value) {
            return value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        }
    }

    private static final class Timing {
        private final long startedAtNanos;
        private volatile Long endedAtNanos;

        private Timing(long startedAtNanos) {
            this.startedAtNanos = startedAtNanos;
        }

        private void finish() {
            if (endedAtNanos == null) endedAtNanos = Long.valueOf(System.nanoTime());
        }

        private Long durationMillis() {
            Long ended = endedAtNanos;
            if (ended == null) return null;
            long elapsed = ended.longValue() - startedAtNanos;
            return Long.valueOf(TimeUnit.NANOSECONDS.toMillis(Math.max(0L, elapsed)));
        }
    }

    private static List<Context> ancestry(Context parent) {
        if (parent == null) return Collections.emptyList();
        List<Context> result = new ArrayList<Context>(parent.ancestry);
        result.add(parent);
        return result;
    }
}
