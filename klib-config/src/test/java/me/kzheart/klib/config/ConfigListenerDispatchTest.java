package me.kzheart.klib.config;

import me.kzheart.klib.config.api.ConfigDocument;
import me.kzheart.klib.scheduler.AsyncTask;
import me.kzheart.klib.scheduler.KScheduler;
import me.kzheart.klib.scheduler.SchedulerFactory;
import me.kzheart.klib.scheduler.TaskHandle;
import me.kzheart.klib.scheduler.Ticks;
import me.kzheart.klib.scope.ScopeImpl;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigListenerDispatchTest {
    @Test
    void schedulesReloadListenersThroughScopeSchedulerWhenAvailable() {
        ScopeImpl scope = new ScopeImpl("config-listener-dispatch");
        RecordingScheduler scheduler = new RecordingScheduler();
        scope.registerCapability(SchedulerFactory.class, ignored -> scheduler);
        YamlConfigCapability capability = YamlConfigCapability.inMemory(
                Collections.singletonMap("settings.yml", "name: klib\n"));
        scope.registerCapability(me.kzheart.klib.scope.capability.ConfigCapability.class, capability);
        ConfigDocument<Settings> document = scope.config(Settings.class, "settings.yml");
        AtomicInteger calls = new AtomicInteger();
        document.onChange(calls::incrementAndGet);

        document.reload();

        assertEquals(0, calls.get());
        assertNotNull(scheduler.scheduled);
        scheduler.scheduled.run();
        assertEquals(1, calls.get());
        scope.close();
    }

    @Test
    void openingDocumentDoesNotNotifyListenerRegisteredAfterInitialLoad() {
        ScopeImpl scope = new ScopeImpl("config-listener-initial-load");
        RecordingScheduler scheduler = new RecordingScheduler();
        scope.registerCapability(SchedulerFactory.class, ignored -> scheduler);
        YamlConfigCapability capability = YamlConfigCapability.inMemory(
                Collections.singletonMap("settings.yml", "name: klib\n"));
        scope.registerCapability(me.kzheart.klib.scope.capability.ConfigCapability.class, capability);

        ConfigDocument<Settings> document = scope.config(Settings.class, "settings.yml");
        AtomicInteger calls = new AtomicInteger();
        document.onChange(calls::incrementAndGet);

        assertEquals(null, scheduler.scheduled);
        assertEquals(0, calls.get());
        scope.close();
    }

    @Test
    void reloadAsyncCompletesAfterScheduledListeners() {
        ScopeImpl scope = new ScopeImpl("config-listener-completion");
        RecordingScheduler scheduler = new RecordingScheduler();
        scope.registerCapability(SchedulerFactory.class, ignored -> scheduler);
        YamlConfigCapability capability = YamlConfigCapability.inMemory(
                Collections.singletonMap("settings.yml", "name: klib\n"));
        scope.registerCapability(me.kzheart.klib.scope.capability.ConfigCapability.class, capability);
        ConfigDocument<Settings> document = scope.config(Settings.class, "settings.yml");
        AtomicInteger calls = new AtomicInteger();
        document.onChange(calls::incrementAndGet);

        CompletionStage<Void> completion = document.reloadAsync();

        assertFalse(completion.toCompletableFuture().isDone());
        scheduler.scheduled.run();
        assertTrue(completion.toCompletableFuture().isDone());
        assertEquals(1, calls.get());
        scope.close();
    }

    public static final class Settings {
        public String name;
    }

    private static final class RecordingScheduler implements KScheduler {
        private Runnable scheduled;

        @Override
        public TaskHandle every(Ticks period, Runnable task) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TaskHandle after(Ticks delay, Runnable task) {
            scheduled = task;
            return new CompletedTask();
        }

        @Override
        public <T> AsyncTask<T> async(Supplier<T> supplier) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class CompletedTask implements TaskHandle {
        private boolean cancelled;

        @Override
        public boolean cancel() {
            boolean changed = !cancelled;
            cancelled = true;
            return changed;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean isDone() {
            return true;
        }
    }
}
