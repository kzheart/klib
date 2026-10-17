package me.kzheart.klib.scheduler;

import me.kzheart.klib.scope.Scope;

@FunctionalInterface
public interface SchedulerFactory {
    KScheduler forScope(Scope scope);
}
