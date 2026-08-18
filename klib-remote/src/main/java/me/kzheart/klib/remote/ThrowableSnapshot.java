package me.kzheart.klib.remote;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 结构化 Throwable 图：分别保留 cause、suppressed 与每层完整堆栈。 */
public final class ThrowableSnapshot {
    private final Map<String, Object> values;

    private ThrowableSnapshot(Map<String, Object> values) {
        this.values = Collections.unmodifiableMap(values);
    }

    public static ThrowableSnapshot capture(Throwable error, IncidentBudget budget) {
        Objects.requireNonNull(error, "error");
        Objects.requireNonNull(budget, "budget");
        return new ThrowableSnapshot(capture(
                error, budget, new IdentityHashMap<Throwable, Boolean>(), new NodeBudget(
                        budget.throwableNodes()), 0));
    }

    public Map<String, Object> toMap() { return values; }

    private static Map<String, Object> capture(
            Throwable error,
            IncidentBudget budget,
            IdentityHashMap<Throwable, Boolean> seen,
            NodeBudget nodes,
            int depth
    ) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (!nodes.claim()) {
            result.put("truncated", "nodes");
            return result;
        }
        result.put("type", error.getClass().getName());
        result.put("message", error.getMessage());
        if (depth >= budget.throwableDepth()) {
            result.put("truncated", "depth");
            return result;
        }
        if (seen.put(error, Boolean.TRUE) != null) {
            result.put("truncated", "reference");
            return result;
        }
        List<Map<String, Object>> frames = new ArrayList<Map<String, Object>>();
        StackTraceElement[] stack = error.getStackTrace();
        int frameLimit = Math.min(stack.length, budget.framesPerThrowable());
        for (int index = 0; index < frameLimit; index++) {
            StackTraceElement frame = stack[index];
            Map<String, Object> encoded = new LinkedHashMap<String, Object>();
            encoded.put("class", frame.getClassName());
            encoded.put("method", frame.getMethodName());
            encoded.put("file", frame.getFileName());
            encoded.put("line", frame.getLineNumber());
            frames.add(encoded);
        }
        result.put("stack", frames);
        if (stack.length > frameLimit) result.put("stack_truncated", stack.length - frameLimit);

        Throwable[] suppressed = error.getSuppressed();
        List<Map<String, Object>> encodedSuppressed = new ArrayList<Map<String, Object>>();
        int suppressedLimit = Math.min(suppressed.length, budget.suppressedPerThrowable());
        for (int index = 0; index < suppressedLimit; index++) {
            if (!nodes.available()) {
                encodedSuppressed.add(truncatedNodes());
                break;
            }
            encodedSuppressed.add(capture(suppressed[index], budget, seen, nodes, depth + 1));
        }
        result.put("suppressed", encodedSuppressed);
        if (suppressed.length > suppressedLimit) {
            result.put("suppressed_truncated", suppressed.length - suppressedLimit);
        }
        if (error.getCause() != null) {
            result.put("cause", nodes.available()
                    ? capture(error.getCause(), budget, seen, nodes, depth + 1)
                    : truncatedNodes());
        }
        return result;
    }

    private static final class NodeBudget {
        private int remaining;

        private NodeBudget(int remaining) { this.remaining = remaining; }

        private boolean claim() {
            if (remaining < 1) return false;
            remaining--;
            return true;
        }

        private boolean available() { return remaining > 0; }
    }

    private static Map<String, Object> truncatedNodes() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("truncated", "nodes");
        return result;
    }
}
