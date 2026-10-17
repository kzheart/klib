/* Copyright (c) 2018 Bkm016. MIT License. Upstream: c27e822fb34eebd7433a94efbfac0a26943cccd6 */
package me.kzheart.klib.script.kether.core

import java.util.concurrent.CompletableFuture
import java.util.function.Consumer

/** 同步动作也经过退出屏障，避免复合动作在 exit 后继续求值输入。 */
internal object ActionExecution {
    @JvmStatic fun <A> process(action: QuestAction<A>, frame: QuestContext.Frame): CompletableFuture<A> {
        val context = frame.context()
        if (context !is AbstractQuestContext<*>) return action.process(frame)
        return synchronized(context) {
            when {
                context.isTerminated -> CompletableFuture<A>().also { it.cancel(false) }
                context.getExitStatus().isPresent -> CompletableFuture.completedFuture(null)
                else -> context.track(action.process(frame))
            }
        }
    }

    /** 完成链双向连接取消，但不会以包装异常替换动作原本的失败。 */
    @JvmStatic fun <T> complete(future: CompletableFuture<T>): Consumer<T> = Consumer { value ->
        if (value !is QuestFuture<*>) future.complete(value)
        else {
            @Suppress("UNCHECKED_CAST")
            val deferred = value.future as CompletableFuture<T>?
            if (deferred == null) future.completeExceptionally(IllegalStateException("Awaited action has no active future"))
            else {
                future.whenComplete { _, _ -> if (future.isCancelled) deferred.cancel(false) }
                deferred.whenComplete { result, failure ->
                    if (failure == null) future.complete(result) else future.completeExceptionally(failure)
                }
            }
        }
    }
}
