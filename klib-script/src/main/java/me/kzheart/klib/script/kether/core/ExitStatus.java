/* Copyright (c) 2018 Bkm016. MIT License. Upstream: c27e822fb34eebd7433a94efbfac0a26943cccd6 */
package me.kzheart.klib.script.kether.core;

import java.util.Objects;

/** 描述任务的完成、暂停或冷却状态。 */
public final class ExitStatus {

    private static final ExitStatus PAUSED = new ExitStatus(true, false, 0);
    private final boolean running;
    private final boolean waiting;
    private final long startTime;

    public ExitStatus(boolean running, boolean waiting, long startTime) {
        this.running = running;
        this.waiting = waiting;
        this.startTime = startTime;
    }

    public boolean isRunning() { return running; }
    public boolean isWaiting() { return waiting; }
    public long getStartTime() { return startTime; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ExitStatus)) return false;
        ExitStatus that = (ExitStatus) other;
        return running == that.running && waiting == that.waiting && startTime == that.startTime;
    }

    @Override
    public int hashCode() { return Objects.hash(running, waiting, startTime); }

    @Override
    public String toString() {
        return "ExitStatus{running=" + running + ", waiting=" + waiting + ", startTime=" + startTime + '}';
    }

    public static ExitStatus success() { return new ExitStatus(false, false, 0); }
    public static ExitStatus paused() { return PAUSED; }
    public static ExitStatus cooldown(long timeout) {
        return new ExitStatus(true, true, System.currentTimeMillis() + timeout);
    }
}
