package me.kzheart.klib.cooldown

import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Predicate
import me.kzheart.klib.scope.Disposable
import me.kzheart.klib.scope.Scope
import org.bukkit.event.player.PlayerQuitEvent

/** Concurrent per-key deadlines with lifecycle-owned cleanup and lazy expiry. */
class Cooldowns<K> private constructor(private val clock: Clock) : Disposable {
    private val deadlines = ConcurrentHashMap<K, Long>()
    private val writes = AtomicInteger()
    fun tryAcquire(key: K, duration: Duration): Attempt {
        requireKey(key)
        val length = nonNegative(duration)
        val now = clock.millis()
        var acquired = false
        val deadline = deadlines.compute(key) { _, current ->
            if (current != null && current > now) current else {
                acquired = true
                if (length == 0L) null else saturatedAdd(now, length)
            }
        }
        afterWrite()
        return if (acquired) Attempt(true, Duration.ZERO) else Attempt(false, Duration.ofMillis(checkNotNull(deadline) - now))
    }
    fun isReady(key: K): Boolean = remainingMillis(key) == 0L
    fun remaining(key: K): Duration = Duration.ofMillis(remainingMillis(key))
    fun set(key: K, duration: Duration) {
        requireKey(key)
        val length = nonNegative(duration)
        if (length == 0L) deadlines.remove(key) else deadlines[key] = saturatedAdd(clock.millis(), length)
        afterWrite()
    }
    fun reduce(key: K, amount: Duration) {
        requireKey(key)
        val length = nonNegative(amount)
        val now = clock.millis()
        deadlines.computeIfPresent(key) { _, current -> (current - length).takeIf { it > now } }
    }
    fun extend(key: K, amount: Duration) {
        requireKey(key)
        val length = nonNegative(amount)
        val now = clock.millis()
        deadlines.compute(key) { _, current -> saturatedAdd(current?.takeIf { it > now } ?: now, length).takeIf { it > now } }
        afterWrite()
    }
    fun reset(key: K) { requireKey(key); deadlines.remove(key) }
    fun clearIf(filter: Predicate<in K>) { deadlines.keys.removeIf(filter) }
    fun clear() = deadlines.clear()
    fun size(): Int { purgeExpired(); return deadlines.size }
    fun purgeExpired() {
        val now = clock.millis()
        val iterator = deadlines.entries.iterator()
        while (iterator.hasNext()) if (iterator.next().value <= now) iterator.remove()
    }
    override fun dispose() = deadlines.clear()
    private fun remainingMillis(key: K): Long {
        requireKey(key)
        val deadline = deadlines[key] ?: return 0
        val remaining = deadline - clock.millis()
        if (remaining <= 0) { deadlines.remove(key, deadline); return 0 }
        return remaining
    }
    private fun afterWrite() { if (writes.incrementAndGet() % 256 == 0) purgeExpired() }
    private fun requireKey(key: K) { if (key == null) throw NullPointerException("key") }
    class Attempt internal constructor(private val acquired: Boolean, private val remaining: Duration) {
        fun acquired(): Boolean = acquired
        fun remaining(): Duration = remaining
    }
    companion object {
        @JvmStatic fun <K> create(): Cooldowns<K> = create(Clock.systemUTC())
        @JvmStatic fun <K> create(clock: Clock): Cooldowns<K> = Cooldowns(clock)
        @JvmStatic fun perPlayer(scope: Scope): Cooldowns<UUID> = perPlayer(scope, Clock.systemUTC())
        @JvmStatic fun perPlayer(scope: Scope, clock: Clock): Cooldowns<UUID> {
            val cooldowns = scope.install(Cooldowns<UUID>(clock))
            scope.on(PlayerQuitEvent::class.java) { cooldowns.reset(it.player.uniqueId) }
            return cooldowns
        }
        private fun nonNegative(duration: Duration): Long {
            require(!duration.isNegative) { "Cooldown duration must not be negative: $duration" }
            return try { duration.toMillis() } catch (_: ArithmeticException) { Long.MAX_VALUE }
        }
        private fun saturatedAdd(left: Long, right: Long): Long {
            val sum = left + right
            return if ((left xor sum) and (right xor sum) < 0) Long.MAX_VALUE else sum
        }
    }
}
