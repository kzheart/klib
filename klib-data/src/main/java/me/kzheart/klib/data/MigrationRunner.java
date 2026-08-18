package me.kzheart.klib.data;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 在各自独立的事务中应用每项待处理迁移。版本读取、迁移主体和版本写入
 * 共用同一事务（在方言支持时采用锁定读），因此并发实例不会重复应用
 * 同一项迁移。
 */
public final class MigrationRunner {
    private MigrationRunner() {
    }

    public static CompletionStage<Integer> apply(StorageSession session, Schema schema) {
        if (session == null) {
            throw new NullPointerException("session");
        }
        if (schema == null) {
            throw new NullPointerException("schema");
        }
        return step(session, schema);
    }

    private static CompletionStage<Integer> step(StorageSession session, Schema schema) {
        return session.transaction(context -> {
            int current = context.schemaVersion(schema.name());
            Migration next = null;
            for (Migration candidate : schema.migrations()) {
                if (candidate.version() > current) {
                    next = candidate;
                    break;
                }
            }
            if (next == null) {
                return new Step(current, true);
            }
            next.apply(context);
            if (!context.compareAndSetSchemaVersion(schema.name(), current, next.version())) {
                throw new StorageException(
                        "Concurrent migration detected for schema " + schema.name());
            }
            return new Step(next.version(), false);
        }).thenCompose(step -> step.done
                ? CompletableFuture.completedFuture(step.version)
                : step(session, schema));
    }

    private static final class Step {
        private final int version;
        private final boolean done;

        private Step(int version, boolean done) {
            this.version = version;
            this.done = done;
        }
    }
}
