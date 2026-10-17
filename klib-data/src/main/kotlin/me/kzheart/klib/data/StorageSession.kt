package me.kzheart.klib.data

import me.kzheart.klib.scope.Disposable
import java.util.Optional
import java.util.concurrent.CompletionStage

/** 异步会话；阻塞 I/O 始终在提供器执行器中运行。 */
@JvmSuppressWildcards
interface StorageSession : Disposable {
    fun get(namespace: String, key: String): CompletionStage<Optional<ByteArray>>
    fun put(namespace: String, key: String, value: ByteArray): CompletionStage<Void>
    fun delete(namespace: String, key: String): CompletionStage<Void>
    fun entries(namespace: String): CompletionStage<Map<String, ByteArray>>
    fun <T> transaction(transaction: StorageTransaction<T>): CompletionStage<T>
}
