package me.kzheart.klib.data.jdbc

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.logging.Logger
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resumeWithException
import me.kzheart.klib.KLogger
import me.kzheart.klib.scope.Scope

class Databases private constructor() {
    companion object {
        @JvmStatic fun open(scope: Scope, config: DatabaseConfig): CompletionStage<Database> {
            val logger = config.logger ?: scope.findCapability(KLogger::class.java).orElseGet { KLogger(Logger.getLogger("KlibDatabase")) }
            val database = Database(DatabaseSource(config.factory), config, logger)
            val result = CompletableFuture<Database>()
            // 提前登记资源，不在完成线程拿 Scope 的生命周期锁，避免关闭排空时互等。
            try { scope.install(database) }
            catch (failure: Throwable) { database.closeAsync(); result.completeExceptionally(failure); return result }
            result.whenComplete { _, _ -> if (result.isCancelled) database.closeAsync() }
            val ready = config.migrations?.let { database.executor.initialize(it, config.targetVersion) }
                ?: CompletableFuture.completedFuture(null)
            ready.thenCompose { database.executor.work(SqlWork { Unit }) }.whenComplete { _, failure ->
                if (failure != null) {
                    database.closeAsync().whenComplete { _, cleanup ->
                        if (cleanup != null && cleanup !== failure) failure.addSuppressed(cleanup)
                        result.completeExceptionally(failure)
                    }
                    logger.warn("Database open failed; resources are being drained")
                } else {
                    if (!result.complete(database)) database.closeAsync()
                    logger.info("Database opened")
                }
            }
            return result
        }
    }
}

/** 打开被取消时立即停止接收；晚到的成功资源也关闭，不阻塞取消线程。 */
suspend fun Databases.Companion.openAwait(scope: Scope, config: DatabaseConfig): Database = suspendCancellableCoroutine { continuation ->
    val pending = open(scope, config).toCompletableFuture()
    continuation.invokeOnCancellation { pending.cancel(false) }
    pending.whenComplete { database, failure ->
        if (failure != null) continuation.resumeWithException(failure)
        else continuation.resume(database) { _, acquired, _ -> acquired.closeAsync() }
    }
}
