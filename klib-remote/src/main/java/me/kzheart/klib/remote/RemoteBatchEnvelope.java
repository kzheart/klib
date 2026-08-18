package me.kzheart.klib.remote;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

final class RemoteBatchEnvelope {
    private final byte[] installationId;
    private final byte[] environment;

    RemoteBatchEnvelope(byte[] installationId, byte[] environment) {
        this.installationId = Arrays.copyOf(installationId, installationId.length);
        this.environment = Arrays.copyOf(environment, environment.length);
    }

    byte[] installationId() { return Arrays.copyOf(installationId, installationId.length); }
    byte[] environment() { return Arrays.copyOf(environment, environment.length); }

    String installationIdText() {
        return new String(installationId, StandardCharsets.UTF_8);
    }

    boolean sameAs(RemoteBatchEnvelope other) {
        return Arrays.equals(installationId, other.installationId)
                && Arrays.equals(environment, other.environment);
    }
}
