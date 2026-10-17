package me.kzheart.klib.script

import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage

/** 适配阶段共享的取消传播语义。 */
class ScriptFutures private constructor() {
    companion object {
        @JvmStatic fun cancelWith(result: CompletableFuture<*>, source: CompletionStage<*>) {
            result.whenComplete { _, _ -> if (result.isCancelled) source.toCompletableFuture().cancel(false) }
        }
        @JvmStatic fun unwrap(failure: Throwable): Throwable {
            var unwrapped = failure
            while (unwrapped is CompletionException && unwrapped.cause != null) unwrapped = unwrapped.cause!!
            return unwrapped
        }
        @JvmStatic fun cancelIfNeeded(result: CompletableFuture<*>, failure: Throwable?): Boolean {
            if (failure == null || unwrap(failure) !is CancellationException) return false
            result.cancel(false)
            return true
        }
    }
}
