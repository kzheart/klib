package me.kzheart.klib.scheduler;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import me.kzheart.klib.KLogger;
import me.kzheart.klib.scope.ScopeImpl;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BukkitSchedulerSyncDispatchTest {
    @Test
    void queuedCommandIsCancelledWhenScopeCloses() {
        Fixture fixture = new Fixture();
        AtomicBoolean executed = new AtomicBoolean();
        fixture.scheduler.syncExecutor().execute(() -> executed.set(true));
        assertEquals(1, fixture.tasks.size());

        fixture.scope.close();
        PendingTask pending = fixture.tasks.get(0);
        assertTrue(pending.cancelled);
        // 即使调度器已取出 Runnable，作用域句柄仍阻止关闭后的副作用。
        pending.command.run();
        assertFalse(executed.get());
    }

    @Test
    void submissionAfterCloseIsSilentlyDropped() {
        Fixture fixture = new Fixture();
        fixture.scope.close();

        fixture.scheduler.syncExecutor().execute(() -> { throw new AssertionError("closed scope"); });

        assertTrue(fixture.tasks.isEmpty());
    }

    @Test
    void openScopeDispatchesOnlyWhenBukkitRunsTask() {
        Fixture fixture = new Fixture();
        AtomicBoolean executed = new AtomicBoolean();
        fixture.scheduler.syncExecutor().execute(() -> executed.set(true));
        assertFalse(executed.get());

        fixture.tasks.get(0).command.run();

        assertTrue(executed.get());
        fixture.scope.close();
        assertFalse(fixture.tasks.get(0).cancelled);
    }

    @Test
    void callbackFailureStillPropagatesAndReleasesTask() {
        Fixture fixture = new Fixture();
        IllegalStateException failure = new IllegalStateException("expected callback failure");
        fixture.scheduler.syncExecutor().execute(() -> { throw failure; });

        assertSame(failure, assertThrows(IllegalStateException.class, fixture.tasks.get(0).command::run));
        fixture.scope.close();
        assertFalse(fixture.tasks.get(0).cancelled);
    }

    @Test
    void syncAndSyncExecutorQueueOnceWithoutInlineReentrancyOrExtraDelay() {
        Fixture fixture = new Fixture();
        List<String> calls = new ArrayList<String>();
        fixture.scheduler.syncExecutor().execute(() -> {
            calls.add("first-start");
            fixture.scheduler.syncExecutor().execute(() -> calls.add("nested"));
            calls.add("first-end");
        });
        fixture.scheduler.sync(() -> calls.add("second"));
        assertEquals(2, fixture.tasks.size());
        assertTrue(calls.isEmpty());

        fixture.tasks.get(0).command.run();
        assertEquals(Arrays.asList("first-start", "first-end"), calls);
        assertEquals(3, fixture.tasks.size());
        fixture.tasks.get(1).command.run();
        fixture.tasks.get(2).command.run();

        assertEquals(Arrays.asList("first-start", "first-end", "second", "nested"), calls);
        assertEquals(3, fixture.tasks.size());
        fixture.scope.close();
    }

    private static final class Fixture {
        private final ScopeImpl scope = new ScopeImpl("bukkit-queued-sync");
        private final List<PendingTask> tasks = new ArrayList<PendingTask>();
        private final Plugin plugin = (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(),
                new Class<?>[]{Plugin.class}, (proxy, method, arguments) -> {
                    throw new AssertionError("Unexpected plugin method: " + method.getName());
                });
        private final BukkitSchedulerAdapter scheduler;

        private Fixture() {
            BukkitScheduler bukkit = (BukkitScheduler) Proxy.newProxyInstance(BukkitScheduler.class.getClassLoader(),
                    new Class<?>[]{BukkitScheduler.class}, (proxy, method, arguments) -> {
                        if (method.getName().equals("runTask") || method.getName().equals("runTaskLater")) {
                            assertSame(plugin, arguments[0]);
                            if (method.getName().equals("runTaskLater")) assertEquals(0L, arguments[2]);
                            PendingTask task = new PendingTask(plugin, tasks.size(), (Runnable) arguments[1]);
                            tasks.add(task);
                            return task;
                        }
                        throw new AssertionError("Unexpected scheduler method: " + method.getName());
                    });
            scheduler = new BukkitSchedulerAdapter(plugin, scope, bukkit,
                    new KLogger(Logger.getLogger("bukkit-sync-test")));
        }
    }

    private static final class PendingTask implements BukkitTask {
        private final Plugin owner;
        private final int id;
        private final Runnable command;
        private boolean cancelled;

        private PendingTask(Plugin owner, int id, Runnable command) {
            this.owner = owner;
            this.id = id;
            this.command = command;
        }

        @Override public int getTaskId() { return id; }
        @Override public Plugin getOwner() { return owner; }
        @Override public boolean isSync() { return true; }
        @Override public boolean isCancelled() { return cancelled; }
        @Override public void cancel() { cancelled = true; }
    }
}
