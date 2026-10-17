package me.kzheart.klib.script

import me.kzheart.klib.script.OpenContainerBridge.ContainerDiscovery
import me.kzheart.klib.script.OpenContainerBridge.RemoteStatementSource
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/** Discovery is snapshotted once; only unresolved results advance to the next container. */
internal class ContainerFallback(private val discovery: ContainerDiscovery) {
    fun resolve(statement: String, context: ScriptContext): CompletionStage<Any?> {
        val found: List<RemoteStatementSource>? = discovery.discover()
        return resolveAt(found?.toList().orEmpty(), 0, statement, context)
    }
    private fun resolveAt(sources: List<RemoteStatementSource>, index: Int, statement: String, context: ScriptContext): CompletionStage<Any?> {
        val result = CompletableFuture<Any?>()
        if (index >= sources.size) {
            result.completeExceptionally(IllegalArgumentException("No shared statement resolved: $statement"))
            return result
        }
        val attempt = sources[index].resolve(statement, context)
            ?: throw NullPointerException("Remote statement source returned null CompletionStage")
        attempt.whenComplete { resolution, failure ->
            when {
                failure != null -> result.completeExceptionally(failure)
                resolution != null && resolution.isResolved -> result.complete(resolution.value())
                else -> resolveAt(sources, index + 1, statement, context).whenComplete { value, nextFailure ->
                    if (nextFailure == null) result.complete(value) else result.completeExceptionally(nextFailure)
                }
            }
        }
        return result
    }
}
