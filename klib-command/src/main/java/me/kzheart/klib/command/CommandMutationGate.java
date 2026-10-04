package me.kzheart.klib.command;

import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Acquires worker exclusion asynchronously; the server thread never waits for command builders. */
final class CommandMutationGate {
    private static final long TIMEOUT_MILLIS = 10000L;
    private CommandMutationGate() { }

    static void submit(ThreadPoolExecutor executor, Queue<Runnable> mainQueue,
                       Runnable mutation, Consumer<Throwable> failure) {
        submit(executor, mainQueue, mutation, failure, TIMEOUT_MILLIS);
    }

    static void submit(ThreadPoolExecutor executor, final Queue<Runnable> mainQueue,
                       final Runnable mutation, final Consumer<Throwable> failure, long timeoutMillis) {
        int workers = executor.getMaximumPoolSize();
        if (executor.isShutdown() || workers <= 0 || workers != executor.getCorePoolSize()
                || !(executor.getQueue() instanceof LinkedBlockingQueue<?>)) {
            throw new IllegalStateException("Paper command builder is not an active fixed FIFO executor");
        }
        final AtomicInteger remaining = new AtomicInteger(workers);
        final Lease lease = new Lease();
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        Runnable barrier = new Runnable() {
            @Override public void run() {
                if (remaining.decrementAndGet() == 0) {
                    mainQueue.add(new Runnable() {
                        @Override public void run() {
                            if (!lease.start()) return;
                            try { mutation.run(); }
                            catch (Throwable problem) { failure.accept(problem); }
                            finally { lease.release.countDown(); }
                        }
                    });
                }
                boolean interrupted = false;
                try {
                    if (!lease.release.await(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                        if (lease.abort()) {
                            lease.release.countDown();
                            failure.accept(new IllegalStateException("Paper command mutation expired before the server consumed its queue"));
                        } else {
                            // A transaction already running on the main thread must retain worker exclusion.
                            interrupted = awaitUninterruptibly(lease.release);
                        }
                    }
                } catch (InterruptedException problem) {
                    interrupted = true;
                    if (lease.abort()) {
                        lease.release.countDown();
                        failure.accept(problem);
                    } else {
                        awaitUninterruptibly(lease.release);
                    }
                } finally {
                    if (interrupted) Thread.currentThread().interrupt();
                }
            }
        };
        try {
            // A FIFO queue and contiguous submissions keep independently shaded coordinator batches ordered.
            for (int index = 0; index < workers; index++) executor.execute(barrier);
        } catch (RuntimeException problem) {
            lease.abort();
            lease.release.countDown();
            throw problem;
        }
    }

    private static boolean awaitUninterruptibly(CountDownLatch release) {
        boolean interrupted = false;
        while (true) {
            try { release.await(); return interrupted; }
            catch (InterruptedException ignored) { interrupted = true; }
        }
    }

    private static final class Lease {
        private final CountDownLatch release = new CountDownLatch(1);
        private boolean valid = true;
        private boolean running;
        synchronized boolean start() {
            if (!valid) return false;
            running = true;
            return true;
        }
        synchronized boolean abort() {
            if (running || !valid) return false;
            valid = false;
            return true;
        }
    }
}
