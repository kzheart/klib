package me.kzheart.klib.scheduler;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import me.kzheart.klib.reflect.Declarations;
import me.kzheart.klib.scope.*;

public final class Tasks {
    private static final AtomicLong IDS = new AtomicLong();
    private final Scope owner;
    public Tasks(Scope owner) { this.owner = Objects.requireNonNull(owner, "owner"); }
    public TaskHandle every(Ticks ticks, Runnable task) { return owner.every(ticks, task); }
    public TaskHandle after(Ticks ticks, Runnable task) { return owner.after(ticks, task); }
    public TaskHandle sync(Runnable task) { return owner.sync(task); }
    public <T> AsyncTask<T> async(Supplier<T> task) { return owner.async(task); }
    public <T> CompletionStage<T> thenSync(CompletionStage<T> stage, Consumer<? super T> success,
            Consumer<? super Throwable> failure) {
        return AsyncTasks.thenSync(stage, owner, success, failure);
    }
    public Disposable register(Object target) {
        Objects.requireNonNull(target, "target");
        List<Method> methods = new ArrayList<Method>();
        for (Method m : Declarations.methods(target.getClass())) {
            Every every = m.getAnnotation(Every.class);
            if (every == null) continue;
            Declarations.voidMethod(m, 0);
            if (every.ticks() <= 0) throw Declarations.invalid(m, "ticks must be positive");
            methods.add(m);
        }
        return owner.scope("tasks-" + IDS.incrementAndGet(), child -> {
            for (Method m : methods) {
                Every every = m.getAnnotation(Every.class);
                AtomicBoolean running = new AtomicBoolean();
                child.every(Ticks.of(every.ticks()), () -> {
                    if (!running.compareAndSet(false, true)) return;
                    if (every.thread() == TaskThread.SYNC) {
                        try { Declarations.invoke(target, m); } finally { running.set(false); }
                    } else {
                        try {
                            child.async(() -> {
                                try { Declarations.invoke(target, m); return null; }
                                finally { running.set(false); }
                            });
                        } catch (RuntimeException | Error failure) { running.set(false); throw failure; }
                    }
                });
            }
        });
    }
}
