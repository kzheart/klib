package me.kzheart.klib.data.cache

import me.kzheart.klib.KLogger
import me.kzheart.klib.scope.Disposable
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.function.Supplier
import java.util.function.UnaryOperator

/**
 * 单飞异步缓存，以版本区分快照和后续修改。仓库负责把阻塞 I/O 放到工作线程。
 * 值最好不可变；save 在缓存锁内调用，使同步编码的仓库取得一致快照。
 * 将编码推迟到其他线程的仓库必须自行确保值不可变。
 */
class PlayerDataCache<T : Any> @JvmOverloads constructor(
    private val repository: PlayerDataRepository<T>,
    private val defaults: Supplier<T>,
    private val unloadedPolicy: UnloadedPolicy,
    private val flushBatchSize: Int,
    private val logger: KLogger? = null
) : Disposable {
    private val lock = Any()
    private val entries = LinkedHashMap<UUID, Entry<T>>()
    private val inFlightSaves = HashSet<CompletableFuture<Void>>()
    private val inFlightOperations = HashSet<CompletableFuture<*>>()
    private var flushCursor: UUID? = null
    private var disposed = false

    init { require(flushBatchSize > 0) { "flushBatchSize must be positive" } }

    /** 启动或加入该玩家唯一一个正在进行的加载。 */
    fun login(playerId: UUID): CompletionStage<T> = login(playerId, false)

    private fun login(playerId: UUID, acceptedBeforeClose: Boolean): CompletionStage<T> {
        val created = synchronized(lock) {
            if (!acceptedBeforeClose) requireOpen()
            entries[playerId]?.let { existing ->
                return if (!existing.quitting) existing.loaded
                else existing.quit.thenCompose { login(playerId) }
            }
            Entry<T>().also { entries[playerId] = it; inFlightOperations.add(it.loaded) }
        }
        val load: CompletionStage<T>? = try {
            repository.load(playerId)
        } catch (error: RuntimeException) {
            finishLoad(playerId, created, null, error)
            return created.loaded
        }
        if (load == null) {
            finishLoad(playerId, created, null,
                IllegalStateException("repository.load returned null stage for player $playerId"))
        } else {
            load.whenComplete { value, error -> finishLoad(playerId, created, value, unwrap(error)) }
        }
        return created.loaded
    }

    fun findLoaded(playerId: UUID?): Optional<T> = synchronized(lock) {
        val entry = entries[playerId]
        if (entry == null || !entry.isLoaded() || entry.quitting) Optional.empty()
        else Optional.of(entry.loadedValue())
    }

    /** 应用修改并将已加载的值标记为脏；未加载数据先读取，绝不静默覆盖已有数据。 */
    fun modify(playerId: UUID, mutation: UnaryOperator<T>): CompletionStage<T> {
        val tracked = synchronized(lock) {
            requireOpen()
            val entry = entries[playerId]
            if (entry != null && entry.isLoaded() && !entry.quitting) return completed(mutate(entry, mutation))
            if (entry?.quitting == true) return failed(IllegalStateException("player is quitting: $playerId"))
            if (unloadedPolicy == UnloadedPolicy.FAIL_FAST) {
                return failed(IllegalStateException("player data is not loaded: $playerId"))
            }
            CompletableFuture<T>().also { inFlightOperations.add(it) }
        }
        val load = try {
            // 修改已被接纳，即使关闭抢先发生也必须允许其前置加载完成。
            login(playerId, true)
        } catch (failure: RuntimeException) {
            completeOperation(tracked, null, failure)
            return tracked
        }
        load.whenComplete { _, loadError ->
            var value: T? = null
            var failure = unwrap(loadError)
            if (failure == null) {
                try {
                    value = synchronized(lock) {
                        val loaded = entries[playerId]
                        check(loaded != null && loaded.isLoaded() && !loaded.quitting) {
                            "player data became unavailable: $playerId"
                        }
                        mutate(loaded, mutation)
                    }
                } catch (error: Throwable) { failure = error }
            }
            completeOperation(tracked, value, failure)
        }
        return tracked
    }

    /** 轮转扫描，每次最多保存配置的批次大小；失败条目继续保持脏状态。 */
    fun flushDirty(): CompletionStage<Void> {
        val batch = synchronized(lock) {
            val selected = ArrayList<Save<T>>()
            val keys = entries.keys.toList()
            if (keys.isNotEmpty()) {
                val start = flushCursor?.let { keys.indexOf(it) + 1 } ?: 0
                for (offset in keys.indices) {
                    if (selected.size >= flushBatchSize) break
                    val key = keys[(start + offset) % keys.size]
                    val entry = entries.getValue(key)
                    if (entry.dirty && entry.isLoaded() && !entry.saving && !entry.quitting) {
                        entry.saving = true
                        selected.add(entry.snapshot(key, false))
                    }
                }
                selected.lastOrNull()?.let { flushCursor = it.playerId }
            }
            selected
        }
        if (batch.isEmpty()) return completed<Void>(null)
        val all = CompletableFuture.allOf(*batch.map { save(it) }.toTypedArray())
        return if (logger == null) all else all.whenComplete { _, error ->
            if (error != null) logger.error(
                "玩家数据批量保存失败（本批 ${batch.size} 名玩家），数据仍标记为脏并会在下次刷新时重试", unwrap(error))
        }
    }

    /** 保存当前值后逐出；正在加载时先等待加载完成。 */
    fun quit(playerId: UUID): CompletionStage<Void> {
        var save: Save<T>? = null
        val result = synchronized(lock) {
            val entry = entries[playerId] ?: return completed<Void>(null)
            if (entry.quitting) return entry.quit
            entry.quitting = true
            if (entry.isLoaded() && !entry.saving) {
                entry.saving = true
                save = entry.snapshot(playerId, true)
            }
            entry.quit
        }
        save?.let { save(it) }
        return result
    }

    fun loadedCount(): Int = synchronized(lock) { entries.values.count { it.isLoaded() && !it.quitting } }

    /** 拒绝新工作，排空已接纳操作与脏数据；首次刷新失败会使返回阶段失败。 */
    fun close(): CompletionStage<Void> {
        synchronized(lock) { disposed = true }
        return CompletableFuture<Void>().also { drain(it) }
    }

    override fun dispose() {
        try {
            close().toCompletableFuture().get(DISPOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            warnUnsaved("关闭时被中断")
            throw IllegalStateException("Interrupted while flushing player data on dispose", error)
        } catch (error: ExecutionException) {
            val cause = error.cause ?: error
            logger?.error("关闭时保存玩家数据失败，仍有 ${unsavedCount()} 名玩家的数据未落盘", cause)
            throw IllegalStateException("Failed to flush player data on dispose", cause)
        } catch (error: TimeoutException) {
            warnUnsaved("关闭超过 $DISPOSE_TIMEOUT_SECONDS 秒仍未完成")
            throw IllegalStateException("Timed out flushing player data on dispose", error)
        }
    }

    private fun warnUnsaved(reason: String) {
        logger?.warn("玩家数据落盘未完成（$reason），仍有 ${unsavedCount()} 名玩家的数据未写入存储")
    }

    private fun unsavedCount(): Int = synchronized(lock) {
        entries.values.count { it.saving || (it.dirty && it.isLoaded()) }
    }

    private fun drain(result: CompletableFuture<Void>) {
        flushDirty().whenComplete { _, flushError ->
            if (flushError != null) {
                result.completeExceptionally(unwrap(flushError))
                return@whenComplete
            }
            val pending: Array<CompletableFuture<*>>
            val idle: Boolean
            synchronized(lock) {
                pending = (inFlightSaves + inFlightOperations).toTypedArray()
                idle = pending.isEmpty() && entries.values.none {
                    it.saving || (it.isLoaded() && it.dirty && !it.quitting)
                }
            }
            if (idle) result.complete(null)
            else CompletableFuture.allOf(*pending).whenComplete { _, _ -> drain(result) }
        }
    }

    private fun finishLoad(playerId: UUID, entry: Entry<T>, loaded: T?, loadError: Throwable?) {
        var value = loaded
        var error = loadError
        if (error == null && value == null) {
            if (unloadedPolicy == UnloadedPolicy.CREATE_DEFAULT) {
                try { value = defaults.get() } catch (failure: RuntimeException) { error = failure }
            }
            if (error == null && value == null) error = IllegalStateException("repository returned null data for player $playerId")
        }
        val quitSave = synchronized(lock) {
            if (entries[playerId] !== entry) return
            inFlightOperations.remove(entry.loaded)
            if (error != null) {
                entries.remove(playerId)
                entry.loaded.completeExceptionally(error)
                if (entry.quitting) entry.quit.completeExceptionally(error)
                return
            }
            entry.value = value
            entry.loaded.complete(value)
            if (entry.quitting) {
                entry.saving = true
                entry.snapshot(playerId, true)
            } else null
        }
        quitSave?.let { save(it) }
    }

    private fun save(save: Save<T>): CompletableFuture<Void> {
        val result = CompletableFuture<Void>()
        synchronized(lock) { inFlightSaves.add(result) }
        val operation: CompletionStage<Void>? = try {
            // 同步编码仓库在同一把锁下读取值快照。
            synchronized(lock) { repository.save(save.playerId, save.value) }
        } catch (error: RuntimeException) {
            finishSaveOperation(save, result, error)
            return result
        }
        if (operation == null) {
            finishSaveOperation(save, result, IllegalStateException("repository.save returned null stage for player ${save.playerId}"))
        } else operation.whenComplete { _, error -> finishSaveOperation(save, result, unwrap(error)) }
        return result
    }

    private fun finishSaveOperation(save: Save<T>, result: CompletableFuture<Void>, failure: Throwable?) {
        finishSave(save, failure)?.let { save(it) }
        synchronized(lock) { inFlightSaves.remove(result) }
        if (failure == null) result.complete(null) else result.completeExceptionally(failure)
    }

    private fun completeOperation(result: CompletableFuture<T>, value: T?, failure: Throwable?) {
        synchronized(lock) { inFlightOperations.remove(result) }
        if (failure == null) result.complete(value) else result.completeExceptionally(failure)
    }

    private fun finishSave(save: Save<T>, error: Throwable?): Save<T>? = synchronized(lock) {
        val current = entries[save.playerId] ?: return null
        if (current !== save.entry) return null
        current.saving = false
        if (error == null) {
            if (current.version == save.version) current.dirty = false
            if (save.evict || (current.quitting && current.version == save.version)) {
                entries.remove(save.playerId)
                current.quit.complete(null)
                return null
            }
            if (current.quitting) {
                current.saving = true
                return current.snapshot(save.playerId, true)
            }
        } else if (save.evict) {
            current.quitting = false
            current.dirty = true
            current.quit.completeExceptionally(error)
            // 失败的退出可重试，不能永久复用已失败的 future。
            current.quit = CompletableFuture()
        } else if (current.quitting) {
            current.saving = true
            return current.snapshot(save.playerId, true)
        }
        null
    }

    private fun mutate(entry: Entry<T>, mutation: UnaryOperator<T>): T {
        val value = requireNotNull(mutation.apply(entry.loadedValue())) { "player data value must not be null" }
        entry.value = value
        entry.version++
        entry.dirty = true
        return value
    }

    private fun requireOpen() { check(!disposed) { "cache is disposed" } }

    private class Entry<T : Any> {
        val loaded = CompletableFuture<T>()
        var quit = CompletableFuture<Void>()
        var value: T? = null
        var version = 0L
        var dirty = false
        var saving = false
        var quitting = false
        fun isLoaded(): Boolean = loaded.isDone && !loaded.isCompletedExceptionally
        fun loadedValue(): T = checkNotNull(value)
        fun snapshot(id: UUID, evict: Boolean) = Save(id, this, loadedValue(), version, evict)
    }

    private class Save<T : Any>(val playerId: UUID, val entry: Entry<T>, val value: T, val version: Long, val evict: Boolean)

    private companion object {
        const val DISPOSE_TIMEOUT_SECONDS = 30L
        fun unwrap(error: Throwable?): Throwable? = if (error is CompletionException) error.cause ?: error else error
        fun <T> completed(value: T?): CompletionStage<T> = CompletableFuture.completedFuture(value)
        fun <T> failed(error: Throwable): CompletionStage<T> = CompletableFuture<T>().also { it.completeExceptionally(error) }
    }
}
