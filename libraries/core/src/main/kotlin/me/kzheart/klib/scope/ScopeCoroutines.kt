@file:JvmName("ScopeCoroutinesApi")

package me.kzheart.klib.scope

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async as coroutineAsync
import kotlinx.coroutines.launch as coroutineLaunch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import me.kzheart.klib.scheduler.SchedulerFactory

/**
 * Coroutine ownership for exactly one build of a Klib scope.
 *
 * Launches default to the server scheduler. Use `withContext(Dispatchers.IO)` for blocking I/O.
 * Closing/rebuilding cancels [job] without waiting for child completion. Cancellation cleanup may
 * run on Dispatchers.Default when the server queue is no longer available; it must not use Bukkit.
 * The [scope] view rejects access after this generation ends, even when the original Scope rebuilds.
 * Never replace the parent Job when launching, or retain unguarded resources across suspension.
 */
public class ScopeCoroutines internal constructor(
    private val lifetime: CoroutineLifetime,
) : CoroutineScope {
    /** A generation-bound scope; keep this view rather than the original rebuildable Scope. */
    public val scope: Scope = lifetime.generation.view()

    /** Supervisor job: one failed child does not cancel its siblings. Parent scopes own child jobs. */
    public val job: Job get() = lifetime.job

    /** Always queues through the captured generation's scheduler; never silently drops continuations. */
    public val mainDispatcher: CoroutineDispatcher = ScopeMainDispatcher(lifetime)

    override val coroutineContext: CoroutineContext =
        job + mainDispatcher + CoroutineName("Klib:${scope.name()}")

    /** Launches an owned coroutine; a replacement Job or cancellation-bypassing start is rejected. */
    public fun launch(
        context: CoroutineContext = EmptyCoroutineContext,
        start: CoroutineStart = CoroutineStart.DEFAULT,
        block: suspend CoroutineScope.() -> Unit,
    ): Job {
        validateLaunch(context, start)
        return coroutineLaunch(context, start, block)
    }

    /** Creates an owned Deferred with the same cancellation and dispatcher rules as [launch]. */
    public fun <T> async(
        context: CoroutineContext = EmptyCoroutineContext,
        start: CoroutineStart = CoroutineStart.DEFAULT,
        block: suspend CoroutineScope.() -> T,
    ): Deferred<T> {
        validateLaunch(context, start)
        return coroutineAsync(context, start, block)
    }

    private fun validateLaunch(context: CoroutineContext, start: CoroutineStart) {
        require(context[Job] == null) { "A Klib coroutine cannot replace its lifecycle Job" }
        require(start == CoroutineStart.DEFAULT || start == CoroutineStart.LAZY) {
            "Klib coroutines support DEFAULT or LAZY start to preserve cancellation"
        }
    }
}

/**
 * Returns the current build's coroutine scope. Repeated reads within a build return the same object;
 * rebuilding returns a fresh object and Job. Requires a ScopeImpl-backed scope and SchedulerFactory.
 * Setup and event registration remain ordinary synchronous calls.
 */
public val Scope.coroutines: ScopeCoroutines
    get() {
        val generation = ScopeGeneration.of(this)
        return generation.call {
            val lifetime = generation.coroutineLifetime()
            lifetime.coroutines ?: run {
                generation.view().requireCapability(SchedulerFactory::class.java)
                ScopeCoroutines(lifetime).also { lifetime.coroutines = it }
            }
        }
    }

internal class CoroutineLifetime(val generation: ScopeGeneration) : Disposable {
    val job: Job = SupervisorJob(generation.parent()?.coroutineLifetime()?.job)
    var coroutines: ScopeCoroutines? = null

    override fun dispose() {
        job.cancel(CancellationException("Scope generation ended: ${generation.view().name()}"))
    }
}

private fun ScopeGeneration.coroutineLifetime(): CoroutineLifetime =
    attachment(CoroutineLifetime::class.java) { CoroutineLifetime(it) }

private class ScopeMainDispatcher(
    private val lifetime: CoroutineLifetime,
) : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        val claimed = AtomicBoolean()
        // Java and Kotlin share registration, generation checks, rejection and queued cancellation.
        lifetime.generation.view().submitSync {
            if (claimed.compareAndSet(false, true)) block.run()
        }.whenComplete { _, failure ->
            if (failure != null && claimed.compareAndSet(false, true)) {
                val cancellation = CancellationException("Klib main dispatcher is no longer available")
                cancellation.initCause(failure)
                try {
                    context[Job]?.cancel(cancellation)
                } finally {
                    // Resume only the already-cancelled continuation, away from the closing thread.
                    // Its finally blocks must not access Bukkit after the generation has ended.
                    Dispatchers.Default.dispatch(context, block)
                }
            }
        }
    }

    override fun toString(): String = "KlibMain[${lifetime.generation.view().name()}]"
}
