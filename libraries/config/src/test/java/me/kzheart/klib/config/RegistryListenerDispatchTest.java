package me.kzheart.klib.config;

import me.kzheart.klib.scheduler.AsyncTask;
import me.kzheart.klib.scheduler.KScheduler;
import me.kzheart.klib.scheduler.SchedulerFactory;
import me.kzheart.klib.scheduler.TaskHandle;
import me.kzheart.klib.scheduler.Ticks;
import me.kzheart.klib.scope.ScopeImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 注册表监听器通过作用域调度器派发，与 YamlConfigDocument 保持一致。 */
class RegistryListenerDispatchTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void schedulesReloadListenersThroughScopeSchedulerWhenAvailable() throws Exception {
        write("entry.yml", "id: one\nvalue: 1\n");
        ScopeImpl scope = new ScopeImpl("registry-listener-dispatch");
        RecordingScheduler scheduler = new RecordingScheduler();
        scope.registerCapability(SchedulerFactory.class, ignored -> scheduler);
        Registry<Entry> registry = Registry.open(
                scope,
                temporaryDirectory,
                Entry.class,
                entry -> entry.id,
                new YamlConfigMapper(),
                false);
        AtomicInteger calls = new AtomicInteger();
        registry.onChange(calls::incrementAndGet);
        assertNull(scheduler.scheduled);

        write("entry.yml", "id: one\nvalue: 2\n");
        registry.reload();

        assertEquals(0, calls.get());
        assertNotNull(scheduler.scheduled);
        scheduler.scheduled.run();
        assertEquals(1, calls.get());
        scope.close();
    }

    private void write(String name, String content) throws Exception {
        Files.write(
                temporaryDirectory.resolve(name),
                content.getBytes(StandardCharsets.UTF_8));
    }

    static final class Entry {
        private String id;
        private int value;
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
