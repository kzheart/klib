package me.kzheart.klib.remote;

/** 构建产物允许 Remote 使用的能力上限；所有能力默认关闭。 */
public final class RemoteCapabilities {
    private final boolean exceptions;
    private final boolean logs;
    private final boolean manualIncidents;

    private RemoteCapabilities(Builder builder) {
        this.exceptions = builder.exceptions;
        this.logs = builder.logs;
        this.manualIncidents = builder.manualIncidents;
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean exceptions() {
        return exceptions;
    }

    public boolean logs() {
        return logs;
    }

    public boolean manualIncidents() {
        return manualIncidents;
    }

    public static final class Builder {
        private boolean exceptions;
        private boolean logs;
        private boolean manualIncidents;

        public Builder exceptions(boolean value) {
            exceptions = value;
            return this;
        }

        public Builder logs(boolean value) {
            logs = value;
            return this;
        }

        public Builder manualIncidents(boolean value) {
            manualIncidents = value;
            return this;
        }

        public RemoteCapabilities build() {
            return new RemoteCapabilities(this);
        }
    }
}
