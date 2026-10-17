package me.kzheart.klib.config

import java.util.Objects
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Supplier

/** One-reference publication boundary; preparing a candidate never mutates the published value. */
class ConfigSnapshot<T>(initialValue: T) {
    private val current = AtomicReference(State(Objects.requireNonNull(initialValue, "initialValue"), 0L))
    fun value(): T = current.get().value
    fun revision(): Long = current.get().revision
    fun prepare(builder: Supplier<out T>): Candidate<T> {
        val base = current.get()
        return Candidate(this, base, Objects.requireNonNull(builder.get(), "candidate value"))
    }
    fun commit(candidate: Candidate<T>) {
        require(candidate.owner === this) { "Candidate belongs to another configuration snapshot" }
        val next = State(candidate.value(), Math.addExact(candidate.base.revision, 1L))
        if (!current.compareAndSet(candidate.base, next)) throw ConfigException("Configuration candidate is stale or already committed")
    }
    class Candidate<T> internal constructor(internal val owner: ConfigSnapshot<T>, internal val base: State<T>, private val value: T) {
        fun value(): T = value
        fun baseRevision(): Long = base.revision
    }
    internal class State<T>(val value: T, val revision: Long)
}
