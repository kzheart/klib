package me.kzheart.klib.remote;

import java.util.Objects;
import java.util.logging.Level;

/** 控制台下发的 Remote 运行策略，应用前必须与构建能力上限取交集。 */
public final class RemotePolicy {
    private final boolean paused;
    private final boolean exceptions;
    private final boolean logs;
    private final boolean manualIncidents;
    private final Level minimumLevel;
    private final int sampleRate;

    private RemotePolicy(Builder builder) {
        paused = builder.paused;
        exceptions = builder.exceptions;
        logs = builder.logs;
        manualIncidents = builder.manualIncidents;
        minimumLevel = builder.minimumLevel;
        sampleRate = builder.sampleRate;
    }

    public static Builder builder() {
        return new Builder();
    }

    static RemotePolicy fromBuild(RemoteCapabilities capabilities) {
        return builder()
                .exceptions(capabilities.exceptions())
                .logs(capabilities.logs())
                .manualIncidents(capabilities.manualIncidents())
                .build();
    }

    public RemotePolicy restrictTo(RemoteCapabilities capabilities) {
        Objects.requireNonNull(capabilities, "capabilities");
        boolean accepting = !paused;
        return builder()
                .paused(paused)
                .exceptions(accepting && exceptions && capabilities.exceptions())
                .logs(accepting && logs && capabilities.logs())
                .manualIncidents(accepting && manualIncidents && capabilities.manualIncidents())
                .minimumLevel(minimumLevel)
                .sampleRate(sampleRate)
                .build();
    }

    public boolean paused() { return paused; }
    public boolean exceptions() { return exceptions; }
    public boolean logs() { return logs; }
    public boolean manualIncidents() { return manualIncidents; }
    public Level minimumLevel() { return minimumLevel; }
    public int sampleRate() { return sampleRate; }

    boolean accepts(Level level) {
        return logs && level.intValue() >= minimumLevel.intValue();
    }

    public static final class Builder {
        private boolean paused;
        private boolean exceptions;
        private boolean logs;
        private boolean manualIncidents;
        private Level minimumLevel = Level.INFO;
        private int sampleRate = 100;

        public Builder paused(boolean value) { paused = value; return this; }
        public Builder exceptions(boolean value) { exceptions = value; return this; }
        public Builder logs(boolean value) { logs = value; return this; }
        public Builder manualIncidents(boolean value) { manualIncidents = value; return this; }

        public Builder minimumLevel(Level value) {
            minimumLevel = Objects.requireNonNull(value, "minimumLevel");
            return this;
        }

        public Builder sampleRate(int value) {
            if (value < 0 || value > 100) {
                throw new IllegalArgumentException("sampleRate must be between 0 and 100");
            }
            sampleRate = value;
            return this;
        }

        public RemotePolicy build() { return new RemotePolicy(this); }
    }
}
