package me.kzheart.klib.ui.prompt;

import me.kzheart.klib.scheduler.TaskHandle;
import me.kzheart.klib.scope.Disposable;
import me.kzheart.klib.scope.Scope;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/** 一个可取消且支持超时的聊天提示。 */
public final class PromptSession<T> implements Disposable {
    private final Scope owner;
    private final PromptParser<T> parser;
    private final CompletableFuture<PromptOutcome<T>> completion =
            new CompletableFuture<PromptOutcome<T>>();
    private final CompletableFuture<PromptOutcome<T>> syncCompletion =
            new CompletableFuture<PromptOutcome<T>>();
    private PromptStatus status = PromptStatus.WAITING;
    private TaskHandle timeoutTask;
    private Executor syncExecutor;

    PromptSession(Scope owner, PromptParser<T> parser) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.parser = Objects.requireNonNull(parser, "parser");
    }

    synchronized void attachTimeout(TaskHandle task) {
        timeoutTask = Objects.requireNonNull(task, "task");
        if (status != PromptStatus.WAITING) {
            task.cancel();
        }
    }

    synchronized void attachSync(Executor executor) {
        syncExecutor = Objects.requireNonNull(executor, "executor");
    }

    /**
     * 向提示输入一行聊天内容。解析器抛出异常时视为解析失败：
     * 提示继续等待，本方法返回 false。
     */
    public boolean submit(String message) {
        Objects.requireNonNull(message, "message");
        synchronized (this) {
            if (status != PromptStatus.WAITING) {
                return false;
            }
        }
        Optional<T> parsed;
        try {
            parsed = Objects.requireNonNull(parser.parse(message), "parser result");
        } catch (RuntimeException failure) {
            return false;
        }
        if (!parsed.isPresent()) {
            return false;
        }
        return finish(PromptStatus.ANSWERED, parsed.get());
    }

    public boolean cancel() {
        return finish(PromptStatus.CANCELLED, null);
    }

    boolean timeout() {
        return finish(PromptStatus.TIMED_OUT, null);
    }

    public synchronized PromptStatus status() {
        return status;
    }

    /**
     * 在完成提示的线程上结束，通常是异步聊天线程。若要操作 Bukkit 状态，
     * 请使用 {@link #completionSync()}。
     */
    public CompletionStage<PromptOutcome<T>> completion() {
        return completion;
    }

    /**
     * 通过所属作用域的调度器（由 {@link ChatPrompt#start} 附加）编组到服务器主线程后完成。
     * 若派发被拒绝，此阶段会异常完成，而不会假装当前线程就是服务器主线程。
     */
    public CompletionStage<PromptOutcome<T>> completionSync() {
        return syncCompletion;
    }

    @Override
    public void dispose() {
        cancel();
    }

    private boolean finish(PromptStatus next, T value) {
        TaskHandle task;
        synchronized (this) {
            if (status != PromptStatus.WAITING) {
                return false;
            }
            status = next;
            task = timeoutTask;
        }
        if (task != null && next != PromptStatus.TIMED_OUT) {
            task.cancel();
        }
        PromptOutcome<T> outcome = new PromptOutcome<T>(next, value);
        completion.complete(outcome);
        marshalSync(outcome);
        owner.remove(this);
        return true;
    }

    private void marshalSync(PromptOutcome<T> outcome) {
        Executor executor;
        synchronized (this) {
            executor = syncExecutor;
        }
        if (executor == null) {
            syncCompletion.completeExceptionally(new IllegalStateException(
                    "prompt has no main-thread completion executor"));
            return;
        }
        try {
            executor.execute(() -> syncCompletion.complete(outcome));
        } catch (RuntimeException scheduleFailure) {
            syncCompletion.completeExceptionally(scheduleFailure);
        }
    }
}
