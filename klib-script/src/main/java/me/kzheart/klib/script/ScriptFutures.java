package me.kzheart.klib.script;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/** 在适配阶段之间保留取消语义。 */
final class ScriptFutures {
    private ScriptFutures() { }

    static void cancelWith(CompletableFuture<?> result, CompletionStage<?> source) {
        result.whenComplete((value, failure) -> {
            if (result.isCancelled()) source.toCompletableFuture().cancel(false);
        });
    }

    static Throwable unwrap(Throwable failure) {
        while (failure instanceof CompletionException && failure.getCause() != null) {
            failure = failure.getCause();
        }
        return failure;
    }

    static boolean cancelIfNeeded(CompletableFuture<?> result, Throwable failure) {
        if (failure != null && unwrap(failure) instanceof CancellationException) {
            result.cancel(false);
            return true;
        }
        return false;
    }
}
