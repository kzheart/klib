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
