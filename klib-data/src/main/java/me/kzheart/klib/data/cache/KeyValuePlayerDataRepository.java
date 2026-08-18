package me.kzheart.klib.data.cache;

import me.kzheart.klib.data.StorageSession;

import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/** 在 {@link StorageSession} 中为每位玩家存储一个编码值。 */
public final class KeyValuePlayerDataRepository<T> implements PlayerDataRepository<T> {
    private final StorageSession session;
    private final String namespace;
    private final DataCodec<T> codec;
    private final Supplier<T> defaults;

    public KeyValuePlayerDataRepository(
            StorageSession session,
            String namespace,
            DataCodec<T> codec,
            Supplier<T> defaults
    ) {
        if (session == null) {
            throw new NullPointerException("session");
        }
        if (namespace == null || namespace.isEmpty()) {
            throw new IllegalArgumentException("namespace must not be empty");
        }
        if (codec == null) {
            throw new NullPointerException("codec");
        }
        if (defaults == null) {
            throw new NullPointerException("defaults");
        }
        this.session = session;
        this.namespace = namespace;
        this.codec = codec;
        this.defaults = defaults;
    }

    @Override
    public CompletionStage<T> load(UUID playerId) {
        return session.get(namespace, playerId.toString())
                .thenApply(stored -> stored.map(codec::decode).orElseGet(defaults));
    }

    @Override
    public CompletionStage<Void> save(UUID playerId, T value) {
        // 同步编码，确保写入排队期间可变值的并发修改不会影响调用方快照。
        byte[] snapshot = codec.encode(value);
        return session.put(namespace, playerId.toString(), snapshot);
    }
}
