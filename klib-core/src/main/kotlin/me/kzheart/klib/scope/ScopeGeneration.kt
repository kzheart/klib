package me.kzheart.klib.scope

import java.util.Optional
import java.util.function.Consumer
import java.util.function.Function
import java.util.function.Supplier

/** A generation is invalidated before any resource in its subtree is released. */
internal class ScopeGeneration(private val owner: ScopeImpl, private val parent: ScopeGeneration?) {
    private val view: Scope = View()
    private val attachments = LinkedHashMap<Class<*>, Disposable>()
    @Volatile private var active = true
    fun parent(): ScopeGeneration? = parent
    fun view(): Scope = view
    fun isActive(): Boolean = active
    fun run(action: Runnable): Boolean = owner.runInGeneration(this, action)
    fun <T> call(action: Supplier<T>): T = owner.inGeneration(this, action)
    fun <T : Disposable> attachment(type: Class<T>, factory: Function<ScopeGeneration, T>): T = call {
        attachments[type]?.let(type::cast) ?: factory.apply(this).also { attachments[type] = it }
    }
    fun invalidate() { active = false }
    fun releaseAttachments(initial: ScopeLifecycleException?, message: String): ScopeLifecycleException? {
        val values = attachments.values.toList()
        attachments.clear()
        var aggregate = initial
        values.asReversed().forEach {
            try { it.dispose() } catch (failure: Throwable) {
                val errors = aggregate ?: ScopeLifecycleException(message).also { aggregate = it }
                errors.addFailure(failure)
            }
        }
        return aggregate
    }

    private inner class View : Scope {
        fun generation(): ScopeGeneration = call { this@ScopeGeneration }
        override fun name(): String = owner.name()
        override fun isClosed(): Boolean = !active
        override fun <T : Disposable> install(resource: T): T = call { owner.install(resource) }
        override fun remove(resource: Disposable) = owner.removeInGeneration(this@ScopeGeneration, resource)
        override fun scope(name: String, configure: Consumer<in Scope>): Scope = call {
            val child = owner.scope(name) { configure.accept(of(it).view()) }
            of(child).view()
        }
        override fun <T : Any> registerCapability(type: Class<T>, capability: T): T = call { owner.registerCapability(type, capability) }
        override fun <T : Any> findCapability(type: Class<T>): Optional<T> = call { owner.findCapability(type) }
        override fun <T : Any> requireCapability(type: Class<T>): T = call { owner.requireCapability(type) }
        override fun rebuild() = call { owner.rebuild() }
        override fun dispose() = close()
        override fun close() = owner.closeInGeneration(this@ScopeGeneration)
    }

    companion object {
        @JvmStatic fun of(scope: Scope): ScopeGeneration = when (scope) {
            is ScopeImpl -> scope.generation()
            is View -> scope.generation()
            else -> throw IllegalArgumentException("Generation-bound tasks require a ScopeImpl-backed scope")
        }
    }
}
