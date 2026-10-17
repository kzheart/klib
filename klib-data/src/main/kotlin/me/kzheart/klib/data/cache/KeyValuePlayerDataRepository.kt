package me.kzheart.klib.data.cache

import me.kzheart.klib.data.StorageSession
import java.util.UUID
import java.util.concurrent.CompletionStage
import java.util.function.Supplier

/** 在 StorageSession 中为每位玩家存储一个编码值。 */
class KeyValuePlayerDataRepository<T>(
    private val session: StorageSession,
    namespace: String?,
    private val codec: DataCodec<T>,
    private val defaults: Supplier<T>
) : PlayerDataRepository<T> {
    private val namespace: String
    init {
        require(!namespace.isNullOrEmpty()) { "namespace must not be empty" }
        this.namespace = namespace
    }
    override fun load(playerId: UUID): CompletionStage<T> =
        session.get(namespace, playerId.toString()).thenApply { stored -> stored.map(codec::decode).orElseGet(defaults) }

    override fun save(playerId: UUID, value: T): CompletionStage<Void> {
        // 同步编码，避免排队期间可变值修改影响调用方快照。
        val snapshot = codec.encode(value)
        return session.put(namespace, playerId.toString(), snapshot)
    }
}
