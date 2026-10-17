package me.kzheart.klib.ui;

import me.kzheart.klib.scheduler.AsyncTask;
import me.kzheart.klib.scheduler.KScheduler;
import me.kzheart.klib.scheduler.SchedulerFactory;
import me.kzheart.klib.scheduler.TaskHandle;
import me.kzheart.klib.scheduler.Ticks;
import me.kzheart.klib.scope.Scope;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

final class ManualSchedulerFactory implements SchedulerFactory {
    private final List<ManualTask> tasks = new ArrayList<ManualTask>();

    @Override
    public KScheduler forScope(final Scope scope) {
        return new KScheduler() {
            @Override
            public TaskHandle every(Ticks period, Runnable task) {
                return add(scope, task, true);
            }

            @Override
            public TaskHandle after(Ticks delay, Runnable task) {
                return add(scope, task, false);
            }

            @Override
            public <T> AsyncTask<T> async(Supplier<T> supplier) {
                throw new UnsupportedOperationException("not needed by UI tests");
            }
        };
    }

    ManualTask latest() {
        return tasks.get(tasks.size() - 1);
    }

    private ManualTask add(Scope scope, Runnable runnable, boolean repeating) {
        ManualTask task = new ManualTask(scope, runnable, repeating);
        tasks.add(task);
        return scope.install(task);
    }

    static final class ManualTask implements TaskHandle {
        private final Scope owner;
        private final Runnable runnable;
        private final boolean repeating;
        private boolean cancelled;
        private boolean done;

        private ManualTask(Scope owner, Runnable runnable, boolean repeating) {
            this.owner = owner;
            this.runnable = runnable;
            this.repeating = repeating;
        }

        void run() {
            if (!cancelled) {
                runnable.run();
                if (!repeating) {
                    done = true;
                    owner.remove(this);
                }
            }
        }

        @Override
        public boolean cancel() {
            if (cancelled || done) {
                return false;
            }
            cancelled = true;
            owner.remove(this);
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean isDone() {
            return done || cancelled;
        }
    }
}
