package me.kzheart.klib.remote;

import java.io.IOException;
import java.util.List;
import java.util.function.Supplier;

/** /ingest/v1 客户端的 Java 返回契约；编码、验证和网络执行由 Kotlin 实现。 */
public final class RemoteClient {
    private final RemoteClientState state;
    RemoteClient(RpkTransport transport, RemoteCapabilities capabilities, Supplier<String> installationId,
                 RemoteEnvironment environment) {
        this(new RemoteClientState(transport, capabilities, installationId, environment));
    }
    private RemoteClient(RemoteClientState state) { this.state = state; }
    public static RemoteClient http(String endpoint, String publicKey, RemoteCapabilities capabilities,
                                    Supplier<String> installationId, RemoteEnvironment environment) {
        return new RemoteClient(RemoteClientState.http(endpoint, publicKey, capabilities, installationId, environment));
    }
    public static RemoteClient insecureLoopback(String endpoint, String publicKey, RemoteCapabilities capabilities,
                                                Supplier<String> installationId, RemoteEnvironment environment) {
        return new RemoteClient(RemoteClientState.insecureLoopback(endpoint, publicKey, capabilities, installationId, environment));
    }
    public RemotePolicy policy() { return state.policy(); }
    public RemoteSettings settings() { return state.settings(); }
    public RemotePolicy refreshPolicy() throws IOException { return state.refreshPolicy(); }
    public RemoteBatchResult sendBatch(List<? extends RemoteEvent> events) throws IOException { return state.sendBatch(events); }
    RemoteBatchEnvelope snapshotEnvelope() { return state.snapshotEnvelope(); }
    byte[] queueIdentity() { return state.queueIdentity(); }
    RemoteBatchResult sendEncodedBatch(RemoteBatchEnvelope envelope, List<byte[]> events, List<String> ids) throws IOException {
        return state.sendEncodedBatch(envelope, events, ids);
    }
    boolean batchFits(RemoteBatchEnvelope envelope, List<byte[]> events, RemoteSettings.Limits limits) throws IOException {
        return state.batchFits(envelope, events, limits);
    }
}
