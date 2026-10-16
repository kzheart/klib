package me.kzheart.klib.data
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.Optional
import java.util.concurrent.CompletableFuture
class StorageCoroutinesTest {
    private class Session : StorageSession {
        val values = linkedMapOf<String, ByteArray>()
        var closed = 0
        override fun get(namespace: String, key: String) = CompletableFuture.completedFuture(Optional.ofNullable(values[key]))
        override fun put(namespace: String, key: String, value: ByteArray): CompletableFuture<Void> {
            values[key] = value.clone(); return CompletableFuture.completedFuture(null)
        }
        override fun delete(namespace: String, key: String): CompletableFuture<Void> {
            values.remove(key); return CompletableFuture.completedFuture(null)
        }
        override fun entries(namespace: String) = CompletableFuture.completedFuture(values.toMap())
        override fun <T> transaction(transaction: StorageTransaction<T>): CompletableFuture<T> = throw UnsupportedOperationException()
        override fun dispose() { closed++ }
    }
    @Test fun `CRUD awaits the same session and reads absent after delete`() = runBlocking {
        val session = Session()
        assertNull(session.getAwait("n", "k"))
        session.putAwait("n", "k", byteArrayOf(1))
        assertArrayEquals(byteArrayOf(1), session.getAwait("n", "k"))
        session.putAwait("n", "k", byteArrayOf(2))
        assertArrayEquals(byteArrayOf(2), session.entriesAwait("n")["k"])
        session.deleteAwait("n", "k")
        assertNull(session.getAwait("n", "k"))
    }
    @Test fun `cancelled acquisition closes eventual session instead of leaking`() = runBlocking {
        val future = CompletableFuture<StorageSession>()
        val provider = object : StorageProvider {
            override fun open() = future
            override fun dispose() {}
        }
        val job = launch(start = CoroutineStart.UNDISPATCHED) { provider.openAwait() }
        job.cancelAndJoin()
        val acquired = Session()
        future.complete(acquired)
        assertEquals(1, acquired.closed)
    }
    @Test fun `cancel between acquisition completion and dispatch releases session`() = runBlocking {
        val queue = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { queue.add(block) }
        }
        val future = CompletableFuture<StorageSession>()
        val provider = object : StorageProvider {
            override fun open() = future
            override fun dispose() {}
        }
        val job = launch(dispatcher, start = CoroutineStart.UNDISPATCHED) { provider.openAwait() }
        val acquired = Session()
        future.complete(acquired)
        job.cancel()
        while (true) (queue.poll() ?: break).run()
        job.join()
        assertEquals(1, acquired.closed)
    }

}
