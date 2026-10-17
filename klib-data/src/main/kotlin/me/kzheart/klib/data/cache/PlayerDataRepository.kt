package me.kzheart.klib.data.cache

import java.util.UUID
import java.util.concurrent.CompletionStage

/** 玩家缓存使用的异步持久化接口。 */
interface PlayerDataRepository<T> {
    fun load(playerId: UUID): CompletionStage<T>
    fun save(playerId: UUID, value: T): CompletionStage<Void>
}
