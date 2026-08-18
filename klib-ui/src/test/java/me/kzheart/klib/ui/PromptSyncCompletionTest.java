package me.kzheart.klib.ui;

import me.kzheart.klib.scheduler.SchedulerFactory;
import me.kzheart.klib.scheduler.Ticks;
import me.kzheart.klib.scope.ScopeImpl;
import me.kzheart.klib.ui.prompt.ChatPrompt;
import me.kzheart.klib.ui.prompt.PromptOutcome;
import me.kzheart.klib.ui.prompt.PromptSession;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptSyncCompletionTest {

    @Test
    void syncCompletionWaitsForOwningScheduler() {
        ManualSchedulerFactory scheduler = new ManualSchedulerFactory();
        ScopeImpl scope = new ScopeImpl("prompt-sync");
        scope.registerCapability(SchedulerFactory.class, scheduler);
        PromptSession<String> session = ChatPrompt.start(
                scope, Ticks.seconds(30L), Optional::of);
        AtomicReference<PromptOutcome<String>> observed =
                new AtomicReference<PromptOutcome<String>>();
        session.completionSync().thenAccept(observed::set);

        session.submit("answer");

        assertNull(observed.get());
        scheduler.latest().run();
        assertEquals("answer", observed.get().value().get());
    }

    @Test
    void syncCompletionFailsWhenMainThreadDispatchIsRejected() {
        AtomicInteger submissions = new AtomicInteger();
        ScopeImpl scope = new ScopeImpl("prompt-sync-rejected");
        scope.registerCapability(SchedulerFactory.class, ignored -> new me.kzheart.klib.scheduler.KScheduler() {
            @Override
            public me.kzheart.klib.scheduler.TaskHandle every(Ticks period, Runnable task) {
                throw new UnsupportedOperationException();
            }

            @Override
            public me.kzheart.klib.scheduler.TaskHandle after(Ticks delay, Runnable task) {
                if (submissions.incrementAndGet() > 1) {
                    throw new IllegalStateException("main thread scheduler rejected completion");
                }
                return new NoopTask();
            }

            @Override
            public <T> me.kzheart.klib.scheduler.AsyncTask<T> async(
                    java.util.function.Supplier<T> supplier
            ) {
                throw new UnsupportedOperationException();
            }
        });
        PromptSession<String> session = ChatPrompt.start(
                scope, Ticks.seconds(30L), Optional::of);

        assertTrue(session.submit("answer"));

        CompletionException failure = assertThrows(
                CompletionException.class,
                () -> session.completionSync().toCompletableFuture().join());
        assertTrue(failure.getCause().getMessage().contains("scheduler rejected"));
        scope.close();
    }

    private static final class NoopTask implements me.kzheart.klib.scheduler.TaskHandle {
        @Override
        public boolean cancel() {
            return true;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean isDone() {
            return false;
        }
    }
}
