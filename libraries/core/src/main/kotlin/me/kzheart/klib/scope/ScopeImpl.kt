package me.kzheart.klib.scope

import java.util.Optional
import java.util.concurrent.locks.ReentrantLock
import java.util.function.Consumer
import java.util.function.Supplier
import kotlin.concurrent.withLock

/** Serializes all lifecycle transitions across a complete scope tree. */
class ScopeImpl private constructor(
    name: String,
    private val parent: ScopeImpl?,
    private val rebuildAction: Consumer<in Scope>?,
    private val lifecycleLock: ReentrantLock
) : Scope {
    private enum class State { OPEN, BUILDING, REBUILDING, CLOSING, CLOSED }
    private val name = name.trim().also { require(it.isNotEmpty()) { "Scope name must not be blank" } }
    private val resources = mutableListOf<Disposable>()
    private val children = HashMap<String, ScopeImpl>()
    private val capabilities = HashMap<Class<*>, Any>()
    private var state = State.OPEN
    private var generation: ScopeGeneration = ScopeGeneration(this, parent?.generation)

    constructor(name: String) : this(name, null, null, ReentrantLock())

    override fun name(): String = name
    override fun isClosed(): Boolean = lifecycleLock.withLock { state == State.CLOSED }

    override fun <T : Disposable> install(resource: T): T = lifecycleLock.withLock {
        require(resource !== this) { "A scope cannot install itself" }
        ensureRegistrationAllowed()
        resources.add(resource)
        resource
    }

    override fun remove(resource: Disposable) = lifecycleLock.withLock {
        if (state == State.OPEN || state == State.BUILDING) resources.remove(resource)
        Unit
    }

    override fun scope(childName: String, configure: Consumer<in Scope>): Scope {
        val normalized = childName.trim().also { require(it.isNotEmpty()) { "Scope name must not be blank" } }
        return lifecycleLock.withLock {
            ensureRegistrationAllowed()
            check(!children.containsKey(normalized)) { "Child scope already exists: $normalized" }
            val child = ScopeImpl(normalized, this, configure, lifecycleLock)
            children[normalized] = child
            resources.add(child)
            try { child.initialize(); child } catch (failure: Throwable) {
                resources.remove(child)
                children.remove(normalized)
                try { child.close() } catch (cleanup: RuntimeException) { failure.addSuppressed(cleanup) }
                throw propagate(failure)
            }
        }
    }

    override fun <T> registerCapability(type: Class<T>, capability: T): T {
        if (capability == null) throw NullPointerException("capability")
        require(type.isInstance(capability)) { "Capability does not implement ${type.name}" }
        return lifecycleLock.withLock {
            ensureRegistrationAllowed()
            check(!capabilities.containsKey(type)) { "Capability already registered: ${type.name}" }
            capabilities[type] = capability
            capability
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> findCapability(type: Class<T>): Optional<T> {
        val local = lifecycleLock.withLock { capabilities[type] }
        return if (local != null) Optional.of<Any>(type.cast(local) as Any) as Optional<T> else parent?.findCapability(type) ?: Optional.empty<Any>() as Optional<T>
    }

    override fun <T> requireCapability(type: Class<T>): T = findCapability(type).orElseThrow {
        IllegalStateException("Required capability is not installed: ${type.name}" +
            "; install the module first (e.g. ConfigModule.install / CommandModule.install)" +
            " in this scope or one of its parents before requiring it")
    }

    override fun rebuild() = lifecycleLock.withLock {
        check(state != State.CLOSED) { "Scope is closed: $name" }
        ensureStableLifecycle()
        closeInternal(true)?.let { throw it }
        generation = ScopeGeneration(this, parent?.generation)
        if (rebuildAction != null) initializeUnderLock() else state = State.OPEN
    }

    override fun dispose() = close()
    override fun close(): Unit = lifecycleLock.withLock {
        if (state == State.CLOSED) return
        ensureStableLifecycle()
        closeInternal(false)?.let { throw it }
        Unit
    }

    private fun ensureStableLifecycle() {
        check(state == State.OPEN) { "Scope lifecycle is already changing: $name" }
    }

    private fun initialize() = lifecycleLock.withLock { initializeUnderLock() }
    private fun initializeUnderLock() {
        state = State.BUILDING
        try {
            checkNotNull(rebuildAction).accept(this)
            state = State.OPEN
        } catch (setupFailure: Throwable) {
            state = State.CLOSING
            val cleanupFailure = releaseResources("Failed to clean up scope $name")
            markClosed()
            cleanupFailure?.let(setupFailure::addSuppressed)
            throw propagate(setupFailure)
        }
    }

    private fun closeInternal(rebuilding: Boolean): ScopeLifecycleException? {
        state = if (rebuilding) State.REBUILDING else State.CLOSING
        val failure = releaseResources((if (rebuilding) "Failed to rebuild scope " else "Failed to close scope ") + name)
        if (rebuilding && failure == null) state = State.OPEN else markClosed()
        return failure
    }

    private fun markClosed() {
        state = State.CLOSED
        if (parent != null && (parent.state == State.OPEN || parent.state == State.BUILDING)) {
            parent.resources.remove(this)
            parent.children.remove(name)
        }
    }

    private fun releaseResources(message: String): ScopeLifecycleException? {
        // Invalidate the entire subtree before cancelling any linked jobs.
        invalidateGenerations()
        var aggregate = releaseGenerationAttachments(null, message)
        for (index in resources.indices.reversed()) {
            try { resources[index].dispose() } catch (failure: Throwable) {
                val errors = aggregate ?: ScopeLifecycleException(message).also { aggregate = it }
                errors.addFailure(failure)
            }
        }
        resources.clear()
        children.clear()
        capabilities.clear()
        return aggregate
    }

    private fun invalidateGenerations() {
        generation.invalidate()
        children.values.forEach { it.invalidateGenerations() }
    }

    private fun releaseGenerationAttachments(initial: ScopeLifecycleException?, message: String): ScopeLifecycleException? {
        var aggregate = generation.releaseAttachments(initial, message)
        children.values.forEach { aggregate = it.releaseGenerationAttachments(aggregate, message) }
        return aggregate
    }

    @JvmName("generation")
    internal fun generation(): ScopeGeneration = lifecycleLock.withLock { ensureRegistrationAllowed(); generation }
    @JvmName("inGeneration")
    internal fun <T> inGeneration(expected: ScopeGeneration, action: Supplier<T>): T = lifecycleLock.withLock {
        check(generation === expected && expected.isActive()) { "Scope generation is no longer active: $name" }
        ensureRegistrationAllowed()
        action.get()
    }
    @JvmName("runInGeneration")
    internal fun runInGeneration(expected: ScopeGeneration, action: Runnable): Boolean = lifecycleLock.withLock {
        if (generation !== expected || !expected.isActive() || state != State.OPEN && state != State.BUILDING) return false
        action.run()
        true
    }
    @JvmName("removeInGeneration")
    internal fun removeInGeneration(expected: ScopeGeneration, resource: Disposable) = lifecycleLock.withLock {
        if (generation === expected && expected.isActive()) remove(resource)
    }
    @JvmName("closeInGeneration")
    internal fun closeInGeneration(expected: ScopeGeneration) = lifecycleLock.withLock {
        if (generation === expected && expected.isActive()) close()
    }
    fun resourceCount(): Int = lifecycleLock.withLock { resources.size }

    private fun ensureRegistrationAllowed() {
        check(generation.isActive() && (state == State.OPEN || state == State.BUILDING)) {
            "Cannot register resources while scope is $state: $name"
        }
    }

    companion object {
        @JvmStatic fun create(name: String, configure: Consumer<in Scope>): ScopeImpl =
            ScopeImpl(name, null, configure, ReentrantLock()).also { it.initialize() }

        private fun propagate(failure: Throwable): RuntimeException = when (failure) {
            is RuntimeException -> failure
            is Error -> throw failure
            else -> ScopeLifecycleException("Scope configuration failed: ${failure.message}", failure)
        }
    }
}
