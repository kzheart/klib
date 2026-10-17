package me.kzheart.klib.config

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.function.Consumer
import java.util.logging.Level
import java.util.logging.Logger
import me.kzheart.klib.scope.Disposable
import me.kzheart.klib.scope.Scope

/** Listener delivery is one boundary: lifetime ownership, dispatch failures and aggregation stay here. */
internal class ReloadListeners(
    private val owner: Scope,
    private val executor: Consumer<Runnable>,
    private val source: () -> String,
    private val active: () -> Boolean,
    private val registry: Boolean,
) {
    private val logger = Logger.getLogger(if (registry) Registry::class.java.name else YamlConfigDocument::class.java.name)
    private val changes = ListenerList()
    private val failures = FailureListenerList()
    @Volatile var lastFailure: Throwable? = null
        private set
    fun addChange(listener: Runnable): Disposable {
        changes.add(listener)
        return changes.registration(listener)
    }
    fun addFailure(listener: Consumer<in Throwable>): Disposable = failures.add(listener)
    fun install(registration: Disposable): Disposable = try { owner.install(registration) }
        catch (failure: RuntimeException) { registration.dispose(); throw failure }
    fun clear() { changes.clear(); failures.clear() }
    fun report(failure: Throwable) {
        val detail = if (registry) "registry reload failed; keeping the last known good snapshot"
            else "configuration reload failed; keeping the last known good value"
        logger.log(Level.SEVERE, "${source()}: $detail", failure)
        failures.snapshot().forEach { listener ->
            try { listener.accept(failure) }
            catch (listenerFailure: RuntimeException) { logger.log(Level.WARNING, "${source()}: reload failure listener failed", listenerFailure) }
        }
    }
    fun dispatch(): CompletionStage<Void> {
        val completion = CompletableFuture<Void>()
        try {
            executor.accept {
                runChanges()
                val failure = lastFailure
                if (failure == null) completion.complete(null) else completion.completeExceptionally(failure)
            }
        } catch (failure: RuntimeException) {
            lastFailure = failure
            logger.log(Level.SEVERE, "${source()}: failed to dispatch ${if (registry) "registry" else "reload"} listeners", failure)
            completion.completeExceptionally(failure)
        }
        return completion
    }
    private fun runChanges() {
        if (!registry) lastFailure = null
        var aggregate: ConfigException? = null
        for (listener in changes.snapshot()) {
            if (!active()) return
            try { listener.run() }
            catch (failure: RuntimeException) {
                val combined = aggregate ?: ConfigException(if (registry) "${source()}: one or more registry listeners failed"
                    else "${source()}:<root>: one or more reload listeners failed").also { aggregate = it }
                combined.addSuppressed(failure)
            }
        }
        if (aggregate != null) logger.log(Level.SEVERE, if (registry)
            "${source()}: one or more registry listeners failed; the new snapshot stays published"
            else "${source()}: one or more reload listeners failed; the new configuration stays loaded", aggregate)
        lastFailure = aggregate
    }
}
