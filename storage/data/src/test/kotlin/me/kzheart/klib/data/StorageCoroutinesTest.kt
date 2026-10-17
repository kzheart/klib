package me.kzheart.klib.data
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.Optional
import java.util.concurrent.CompletableFuture
class StorageCoroutinesTest {
    private class Session : StorageSession {
        val values = linkedMapOf<String, ByteArray>()
        @Volatile var closed = 0
        var onClose: (() -> Unit)? = null
        var closeFailure: RuntimeException? = null
        override fun get(namespace: String, key: String) = CompletableFuture.completedFuture(Optional.ofNullable(values[key]))
        override fun put(namespace: String, key: String, value: ByteArray): CompletableFuture<Void> {
            values[key] = value.clone(); return CompletableFuture.completedFuture(null)
        }
        override fun delete(namespace: String, key: String): CompletableFuture<Void> {
            values.remove(key); return CompletableFuture.completedFuture(null)
        }
        override fun entries(namespace: String) = CompletableFuture.completedFuture(values.toMap())
        override fun <T> transaction(transaction: StorageTransaction<T>): CompletableFuture<T> = throw UnsupportedOperationException()
        override fun dispose() { onClose?.invoke(); closed++; closeFailure?.let { throw it } }
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
        withTimeout(5000) { while (acquired.closed == 0) delay(1) }
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
        withTimeout(5000) { while (acquired.closed == 0) delay(1) }
        assertEquals(1, acquired.closed)
    }

    private fun provider(session: Session) = object : StorageProvider {
        override fun open() = CompletableFuture.completedFuture<StorageSession>(session)
        override fun dispose() {}
    }
    @Test fun `session block closes on successful suspended work`() = runBlocking {
        val session = Session()
        val value = provider(session).withSession {
            putAwait("n", "k", byteArrayOf(7))
            yield()
            getAwait("n", "k")!![0]
        }
        assertEquals(7.toByte(), value)
        assertEquals(1, session.closed)
    }
    @Test fun `session block preserves failure and suppresses close failure`() = runBlocking {
        val session = Session()
        val expected = IllegalArgumentException("business")
        val closing = IllegalStateException("closing")
        session.closeFailure = closing
        try {
            provider(session).withSession<Unit> { throw expected }
            fail<Unit>("expected failure")
        } catch (failure: IllegalArgumentException) {
            assertSame(expected, failure)
            assertSame(closing, failure.suppressed.single())
        }
        assertEquals(1, session.closed)
    }
    @Test fun `session block closes when cancelled while suspended`() = runBlocking {
        val session = Session()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            provider(session).withSession { awaitCancellation() }
        }
        job.cancelAndJoin()
        assertEquals(1, session.closed)
    }

    @Test fun `blocking close does not block caller dispatcher and cancellation awaits cleanup`() = runBlocking {
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "storage-test-main") }
        val dispatcher = executor.asCoroutineDispatcher()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val session = Session()
        session.onClose = {
            assertNotEquals("storage-test-main", Thread.currentThread().name)
            entered.countDown()
            check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
        }
        try {
            val started = CompletableDeferred<Unit>()
            val job = launch(dispatcher) { provider(session).withSession { started.complete(Unit); awaitCancellation() } }
            started.await()
            job.cancel()
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, java.util.concurrent.TimeUnit.SECONDS) })
            withTimeout(5000) { withContext(dispatcher) { assertFalse(job.isCompleted) } }
            release.countDown()
            job.join()
            assertEquals(1, session.closed)
        } finally { release.countDown(); dispatcher.close() }
    }

    @Test fun `cancelled acquisition dispatches blocking disposal away from completion thread`() = runBlocking {
        val future = CompletableFuture<StorageSession>()
        val provider = object : StorageProvider {
            override fun open() = future
            override fun dispose() {}
        }
        val acquired = Session()
        val completingThread = Thread.currentThread()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        acquired.onClose = {
            assertNotSame(completingThread, Thread.currentThread())
            entered.countDown()
            check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
        }
        val job = launch(start = CoroutineStart.UNDISPATCHED) { provider.openAwait() }
        job.cancelAndJoin()
        try {
            future.complete(acquired)
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, java.util.concurrent.TimeUnit.SECONDS) })
            assertEquals(0, acquired.closed)
        } finally { release.countDown() }
        withTimeout(5000) { while (acquired.closed == 0) delay(1) }
        assertEquals(1, acquired.closed)
    }

    @Test fun `orphaned acquisition cleanup failure reaches owner exception handler`() = runBlocking {
        val reported = CompletableDeferred<Throwable>()
        val handler = CoroutineExceptionHandler { _, failure -> reported.complete(failure) }
        val future = CompletableFuture<StorageSession>()
        val provider = object : StorageProvider {
            override fun open() = future
            override fun dispose() {}
        }
        val job = launch(handler, start = CoroutineStart.UNDISPATCHED) { provider.openAwait() }
        job.cancelAndJoin()
        val expected = IllegalStateException("orphan close")
        val acquired = Session().apply { closeFailure = expected }
        future.complete(acquired)
        assertSame(expected, withTimeout(5000) { reported.await() })
        assertEquals(1, acquired.closed)
    }

}
