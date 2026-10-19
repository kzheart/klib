@file:JvmName("OperationObservations")

package me.kzheart.klib.scope

import java.util.concurrent.CompletionStage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.future.asDeferred

/** The provider cancelled its result; this says nothing about an accepted operation's effects. */
public class SourceOperationCancelledException(cause: CancellationException) :
    IllegalStateException("Source operation cancelled; its outcome is unknown", cause)

/**
 * Observes an accepted operation without propagating observer cancellation to its source.
 * Cancelling the calling coroutine still stops observation promptly. Source cancellation is an
 * ordinary failure, not cancellation of the caller, and must never be interpreted as rejection.
 * This does not start, retry, or reconcile the operation and does not change its completion thread.
 */
public suspend fun <T> CompletionStage<T>.observeOperation(): T = try {
    asDeferred().await()
} catch (cancelled: CancellationException) {
    currentCoroutineContext().ensureActive()
    throw SourceOperationCancelledException(cancelled)
}
