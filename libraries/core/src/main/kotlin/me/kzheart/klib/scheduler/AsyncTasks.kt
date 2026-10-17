package me.kzheart.klib.scheduler

import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.function.Consumer
import java.util.function.Function
import me.kzheart.klib.scope.Scope

/** Generation-bound bridges for Java stages, sharing the scope's cancellation core. */
class AsyncTasks private constructor() {
    companion object {
        @JvmStatic fun <T> thenSync(stage: CompletionStage<T>, scope: Scope, action: Consumer<in T>): CompletionStage<T> =
            thenSync(stage, scope, action, null)
        @JvmStatic fun <T> thenSync(stage: CompletionStage<T>, scope: Scope, action: Consumer<in T>,
                                   onError: Consumer<in Throwable>?): CompletionStage<T> =
            scope.submitSync(stage.handle { value, failure -> Outcome(value, failure) }) { outcome ->
                val failure = outcome.failure
                if (failure == null) action.accept(outcome.value) else {
                    if (onError != null) {
                        try { onError.accept(unwrap(failure)) } catch (callbackFailure: Throwable) {
                            if (callbackFailure !== failure) failure.addSuppressed(callbackFailure)
                        }
                    }
                    throw if (failure is CompletionException) failure else CompletionException(failure)
                }
                outcome.value
            }
        @JvmStatic fun <T> onSync(stage: CompletionStage<T>, scope: Scope): CompletionStage<T> =
            scope.submitSync(stage, Function.identity())
        @JvmStatic fun unwrap(failure: Throwable): Throwable {
            var current = failure
            while (current is CompletionException || current is ExecutionException) {
                val cause = current.cause ?: break
                if (cause === current) break
                current = cause
            }
            return current
        }
    }
    private class Outcome<T>(val value: T, val failure: Throwable?)
}
