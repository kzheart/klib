package me.kzheart.klib.data

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/** 每项迁移独立提交，版本读取、主体和 CAS 共用同一事务。跨实例互斥由后端锁语义保证。 */
class MigrationRunner private constructor() {
    companion object {
        @JvmStatic
        fun apply(session: StorageSession, schema: Schema): CompletionStage<Int> = step(session, schema)

        private fun step(session: StorageSession, schema: Schema): CompletionStage<Int> =
            session.transaction { context ->
                val current = context.schemaVersion(schema.name())
                if (current < 0 || current > schema.version()) {
                    throw StorageException("Unsupported schema version $current for ${schema.name()}; latest declared version is ${schema.version()}")
                }
                val next = schema.migrations().firstOrNull { it.version() > current }
                if (next == null) Step(current, true) else {
                    next.apply(context)
                    if (!context.compareAndSetSchemaVersion(schema.name(), current, next.version())) {
                        throw StorageException("Concurrent migration detected for schema ${schema.name()}")
                    }
                    Step(next.version(), false)
                }
            }.thenCompose { if (it.done) CompletableFuture.completedFuture(it.version) else step(session, schema) }
    }
    private class Step(val version: Int, val done: Boolean)
}
