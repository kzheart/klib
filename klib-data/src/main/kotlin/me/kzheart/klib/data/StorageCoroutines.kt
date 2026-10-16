package me.kzheart.klib.data

import kotlinx.coroutines.future.await
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resumeWithException

/** Awaits provider I/O without moving a blocking transaction onto a server thread. */
suspend fun StorageProvider.openAwait(): StorageSession = suspendCancellableCoroutine { continuation ->
    // Do not cancel acquisition: its eventual resource must be observed and disposed.
    open().whenComplete { session, failure ->
        if (failure != null) continuation.resumeWithException(failure)
        else continuation.resume(session) { _, acquired, _ -> acquired.dispose() }
    }
}
suspend fun StorageSession.getAwait(namespace: String, key: String): ByteArray? =
    get(namespace, key).await().orElse(null)
suspend fun StorageSession.putAwait(namespace: String, key: String, value: ByteArray) {
    put(namespace, key, value).await()
}
suspend fun StorageSession.deleteAwait(namespace: String, key: String) { delete(namespace, key).await() }
suspend fun StorageSession.entriesAwait(namespace: String): Map<String, ByteArray> = entries(namespace).await()

/** The transaction body is deliberately NON-suspending and stays on the provider executor. */
suspend fun <T> StorageSession.transactionAwait(block: TransactionContext.() -> T): T =
    transaction { it.block() }.await()

/** Owns an async session for a suspending block, closing it on success, failure or cancellation.
 * The block may suspend between operations; transactionAwait bodies remain non-suspending.
 */
suspend fun <T> StorageProvider.withSession(block: suspend StorageSession.() -> T): T {
    val session = openAwait()
    var failure: Throwable? = null
    try {
        return session.block()
    } catch (cause: Throwable) {
        failure = cause
        throw cause
    } finally {
        try { session.dispose() } catch (closeFailure: Throwable) {
            if (failure == null) throw closeFailure
            if (failure !== closeFailure) failure.addSuppressed(closeFailure)
        }
    }
}
