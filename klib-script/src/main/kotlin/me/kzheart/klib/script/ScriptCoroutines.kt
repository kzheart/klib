package me.kzheart.klib.script

import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.future.await
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Uses the existing engine. Cancellation runs its cleanup on the cancelling thread, which can be
 * a timeout/background thread even when evaluation starts on the server thread. Use the Executor
 * overload when native frame cleanup has thread affinity.
 */
suspend fun ScriptEngine.evalAwait(script: String, context: ScriptContext): Any? = eval(script, context).await()

/** Same cancellation-thread rules as the two-argument [evalAwait]. */
suspend fun ScriptEngine.evalConditionAwait(script: String, context: ScriptContext): Boolean =
    evalCondition(script, context).await()

/**
 * Requests cancellation through [cancellationExecutor]; evaluation still starts on the caller's
 * thread. The executor must run accepted tasks and remain available until native cleanup finishes.
 * Cancellation may finish the coroutine before the queued native cleanup runs. Rejection is reported
 * through the coroutine exception handler, never retried on the wrong thread; a [ScopedScriptRuntime]
 * owner must then close/cancelPending on the host thread. Scope.syncExecutor can silently drop tasks
 * after closure, so it is not a reliable standalone cleanup executor.
 */
suspend fun ScriptEngine.evalAwait(
    script: String,
    context: ScriptContext,
    cancellationExecutor: Executor,
): Any? = eval(script, context).awaitCancellingOn(cancellationExecutor)

/** Same explicit cancellation-executor contract as the three-argument [evalAwait]. */
suspend fun ScriptEngine.evalConditionAwait(
    script: String,
    context: ScriptContext,
    cancellationExecutor: Executor,
): Boolean = evalCondition(script, context).awaitCancellingOn(cancellationExecutor)

private suspend fun <T> CompletionStage<T>.awaitCancellingOn(executor: Executor): T =
    suspendCancellableCoroutine { continuation ->
        val future = toCompletableFuture()
        continuation.invokeOnCancellation {
            if (!future.isDone) executor.execute { future.cancel(false) }
        }
        future.whenComplete { value, failure ->
            if (failure == null) continuation.resume(value)
            else continuation.resumeWithException(
                if (failure is CompletionException) failure.cause ?: failure else failure,
            )
        }
    }

fun scriptContext(block: ScriptContext.Builder.() -> Unit): ScriptContext =
    ScriptContext.builder().apply(block).build()
