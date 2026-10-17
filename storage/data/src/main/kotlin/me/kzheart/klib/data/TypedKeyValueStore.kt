package me.kzheart.klib.data

import java.util.Collections
import java.util.Optional
import java.util.concurrent.CompletionStage
import java.util.function.Function
import kotlinx.coroutines.future.await
import me.kzheart.klib.data.cache.DataCodec

/** 字节契约上的薄类型视图；缺失键为 empty，codec 不允许用 null 表示已存值。 */
class TypedKeyValueStore<T : Any>(private val session: StorageSession, private val namespace: String, private val codec: DataCodec<T>) {
    init { require(namespace.isNotBlank()) { "namespace must not be blank" } }
    fun get(key: String): CompletionStage<Optional<T>> = session.get(namespace, key).thenApply { stored -> stored.map { decode(it) } }
    fun put(key: String, value: T): CompletionStage<Void> = session.put(namespace, key, requireNotNull(codec.encode(value)).clone())
    fun delete(key: String): CompletionStage<Void> = session.delete(namespace, key)
    fun entries(): CompletionStage<Map<String, T>> = session.entries(namespace).thenApply { stored ->
        Collections.unmodifiableMap(stored.mapValuesTo(LinkedHashMap()) { decode(it.value) })
    }
    fun <R> transactionAsync(action: Function<in TypedKeyValueView<T>, out R>): CompletionStage<R> = session.transaction { tx ->
        val view = TypedKeyValueView(tx, namespace, codec)
        try { action.apply(view) } finally { view.finish() }
    }
    private fun decode(bytes: ByteArray): T = requireNotNull(codec.decode(bytes.clone())) { "codec decoded a null stored value" }
}

/** 仅在当前 KV 事务回调内有效，不与关系型 SQL 事务合并。 */
class TypedKeyValueView<T : Any> internal constructor(private val store: KeyValueStore, private val namespace: String,
                                                     private val codec: DataCodec<T>) {
    private val owner = Thread.currentThread()
    private var active = true
    private fun checkActive() { check(active && owner === Thread.currentThread()) { "Typed KV view is only valid inside its transaction callback" } }
    fun get(key: String): Optional<T> { checkActive(); return store.get(namespace, key).map { requireNotNull(codec.decode(it.clone())) } }
    fun put(key: String, value: T) { checkActive(); store.put(namespace, key, requireNotNull(codec.encode(value)).clone()) }
    fun delete(key: String) { checkActive(); store.delete(namespace, key) }
    internal fun finish() { active = false }
}

fun <T : Any> StorageSession.typed(namespace: String, codec: DataCodec<T>): TypedKeyValueStore<T> = TypedKeyValueStore(this, namespace, codec)
suspend fun <T : Any> TypedKeyValueStore<T>.getAwait(key: String): T? = get(key).await().orElse(null)
suspend fun <T : Any> TypedKeyValueStore<T>.putAwait(key: String, value: T) { put(key, value).await() }
suspend fun <T : Any> TypedKeyValueStore<T>.deleteAwait(key: String) { delete(key).await() }
suspend fun <T : Any> TypedKeyValueStore<T>.entriesAwait(): Map<String, T> = entries().await()
suspend fun <T : Any, R> TypedKeyValueStore<T>.transaction(block: TypedKeyValueView<T>.() -> R): R = transactionAsync { it.block() }.await()
