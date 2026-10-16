package me.kzheart.klib.scope

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import me.kzheart.klib.scheduler.AsyncTask
import me.kzheart.klib.scheduler.KScheduler
import me.kzheart.klib.scheduler.SchedulerFactory
import me.kzheart.klib.scheduler.TaskHandle
import me.kzheart.klib.scheduler.Ticks
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.function.Supplier

@OptIn(ExperimentalCoroutinesApi::class)
class ScopeCoroutinesTest {
    @Test
    fun `setup stays synchronous and launch is queued on the main scheduler`() {
        val queue = MainQueue()
        var setupDone = false
        var ran = false
        val caller = Thread.currentThread()
        lateinit var task: Job
        val root = scope("native") {
            registerCapability<SchedulerFactory>(queue)
            setupDone = true
            task = coroutines.launch {
                assertSame(caller, Thread.currentThread())
                ran = true
            }
        }
        assertTrue(setupDone)
        assertFalse(ran)
        assertSame(root.coroutines, root.coroutines)
        queue.drain()
        assertTrue(ran)
        assertTrue(task.isCompleted)
        root.close()
    }

    @Test
    fun `closing before launch dispatch completes cancellation without executing the body`() {
        val queue = MainQueue()
        val root = newScope(queue)
        val runtime = root.coroutines
        val calls = AtomicInteger()
        val task = runtime.launch { calls.incrementAndGet() }
        root.close()
        awaitCompletion(task)
        queue.drain()
        assertEquals(0, calls.get())
        assertTrue(task.isCancelled)
        assertTrue(runtime.job.isCancelled)
        assertThrows(IllegalStateException::class.java) { root.coroutines }
        val late = runtime.launch { calls.incrementAndGet() }
        awaitCompletion(late)
        assertEquals(0, calls.get())
    }

    @Test
    fun `close cancels a delayed coroutine and runs finally without waiting on main`() {
        val queue = MainQueue()
        val root = newScope(queue)
        val cleanup = CountDownLatch(1)
        val reachedAfterDelay = AtomicInteger()
        val task = root.coroutines.launch {
            try {
                delay(Long.MAX_VALUE)
                reachedAfterDelay.incrementAndGet()
            } finally {
                cleanup.countDown()
            }
        }
        queue.drain()
        root.close()
        assertTrue(cleanup.await(5, TimeUnit.SECONDS))
        awaitCompletion(task)
        assertEquals(0, reachedAfterDelay.get())
    }

    @Test
    fun `queued resumption is drained into cancellation even when its task handle is cancelled`() {
        val queue = MainQueue()
        val root = newScope(queue)
        val waiting = CompletableDeferred<Unit>()
        val cleanup = CountDownLatch(1)
        val effects = AtomicInteger()
        val task = root.coroutines.launch {
            try {
                waiting.await()
                effects.incrementAndGet()
            } finally {
                cleanup.countDown()
            }
        }
        queue.drain()
        waiting.complete(Unit)
        assertTrue(queue.hasTasks())
        root.close()
        assertTrue(cleanup.await(5, TimeUnit.SECONDS))
        awaitCompletion(task)
        queue.drain()
        assertEquals(0, effects.get())
    }

    @Test
    fun `rebuild uses a fresh Job and old scope view cannot see or dispose the new generation`() {
        val queue = MainQueue()
        val generations = AtomicInteger()
        val root = scope("rebuild") {
            registerCapability<SchedulerFactory>(queue)
            registerCapability<CharSequence>("generation-${generations.incrementAndGet()}")
        }
        val first = root.coroutines
        val oldTask = first.launch { awaitCancellation() }
        queue.drain()
        root.rebuild()
        val second = root.coroutines
        assertNotSame(first, second)
        assertNotSame(first.job, second.job)
        assertTrue(first.job.isCancelled)
        assertTrue(second.job.isActive)
        assertThrows(IllegalStateException::class.java) { first.scope.requireCapability<CharSequence>() }
        assertThrows(IllegalStateException::class.java) { first.scope.install(Disposable {}) }
        assertThrows(IllegalStateException::class.java) { first.scope.coroutines }
        first.scope.close()
        assertFalse(root.isClosed)
        var value: CharSequence? = null
        val newTask = second.launch { value = second.scope.requireCapability<CharSequence>() }
        queue.drain()
        assertEquals("generation-2", value)
        assertTrue(newTask.isCompleted)
        awaitCompletion(oldTask)
        root.close()
    }

    @Test
    fun `old noncancellable IO cleanup cannot register into or read the rebuilt scope`() {
        val queue = MainQueue()
        val root = newScope(queue)
        val first = root.coroutines
        val entered = CountDownLatch(1)
        val release = CompletableDeferred<Unit>()
        val rejectedRead = CompletableDeferred<Boolean>()
        val task = first.launch {
            withContext(Dispatchers.Default + NonCancellable) {
                entered.countDown()
                release.await()
                rejectedRead.complete(runCatching { first.scope.requireCapability<SchedulerFactory>() }.isFailure)
            }
        }
        queue.drain()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        root.rebuild()
        release.complete(Unit)
        awaitCompletion(task)
        assertTrue(rejectedRead.isCompleted)
        assertTrue(rejectedRead.getCompleted())
        root.close()
    }

    @Test
    fun `every descendant Job is cancelled before any ordinary resource is released`() {
        val queue = MainQueue()
        val root = newScope(queue)
        val rootRuntime = root.coroutines
        val child = root.scope("child") {}
        val childRuntime = child.coroutines
        val task = childRuntime.launch { awaitCancellation() }
        queue.drain()
        root.install(Disposable {
            assertTrue(rootRuntime.job.isCancelled)
            assertTrue(childRuntime.job.isCancelled)
            assertTrue(childRuntime.scope.isClosed)
        })
        root.close()
        awaitCompletion(task)
    }

    @Test
    fun `parent Job cancellation propagates and sibling launch failure is supervised`() {
        val queue = MainQueue()
        val root = newScope(queue)
        val parentRuntime = root.coroutines
        val child = root.scope("child") {}
        val runtime = child.coroutines
        val error = AtomicReference<Throwable>()
        val handler = CoroutineExceptionHandler { _, failure -> error.set(failure) }
        val bad = runtime.launch(handler) { error("expected") }
        val sibling = runtime.launch { awaitCancellation() }
        queue.drain()
        assertTrue(bad.isCompleted)
        assertEquals("expected", error.get().message)
        assertTrue(sibling.isActive)
        assertTrue(runtime.job.isActive)
        parentRuntime.job.cancel()
        queue.drain()
        awaitCompletion(sibling)
        assertTrue(runtime.job.isCancelled)
        root.close()
    }

    @Test
    fun `scheduler rejection cancels continuations and still executes finally`() {
        val queue = MainQueue()
        val root = newScope(queue)
        val waiting = CompletableDeferred<Unit>()
        val cleaned = CountDownLatch(1)
        val effects = AtomicInteger()
        val task = root.coroutines.launch {
            try {
                waiting.await()
                effects.incrementAndGet()
            } finally {
                cleaned.countDown()
            }
        }
        queue.drain()
        queue.rejected = true
        waiting.complete(Unit)
        assertTrue(cleaned.await(5, TimeUnit.SECONDS))
        awaitCompletion(task)
        assertTrue(task.isCancelled)
        assertEquals(0, effects.get())
        root.close()
    }

    @Test
    fun `one broken queue handle cannot strand the other cancelled continuations`() {
        val queue = MainQueue().also { it.failCancellation = true }
        val root = newScope(queue)
        val runtime = root.coroutines
        val first = runtime.launch { fail<Unit>("cancelled first body ran") }
        val second = runtime.launch { fail<Unit>("cancelled second body ran") }
        assertThrows(ScopeLifecycleException::class.java) { root.close() }
        awaitCompletion(first)
        awaitCompletion(second)
        assertTrue(first.isCancelled)
        assertTrue(second.isCancelled)
        queue.drain()
    }

    @Test
    fun `setup failure cancels jobs and teardown failure leaves no new generation`() {
        val queue = MainQueue()
        lateinit var runtime: ScopeCoroutines
        lateinit var task: Job
        assertThrows(IllegalArgumentException::class.java) {
            scope("setup-fails") {
                registerCapability<SchedulerFactory>(queue)
                runtime = coroutines
                task = runtime.launch { fail("must not execute") }
                throw IllegalArgumentException("setup")
            }
        }
        awaitCompletion(task)
        assertTrue(runtime.job.isCancelled)
        val root = newScope(queue)
        val before = root.coroutines
        root.install(Disposable { throw IllegalStateException("dispose") })
        assertThrows(ScopeLifecycleException::class.java) { root.rebuild() }
        assertTrue(root.isClosed)
        assertTrue(before.job.isCancelled)
        assertThrows(IllegalStateException::class.java) { root.coroutines }
    }

    @Test
    fun `async returns a typed Deferred and dangerous launch overrides are rejected`() {
        val queue = MainQueue()
        val root = newScope(queue)
        val runtime = root.coroutines
        val result = runtime.async { "typed" }
        queue.drain()
        assertEquals("typed", result.getCompleted())
        val replacement = Job()
        try {
            assertThrows(IllegalArgumentException::class.java) { runtime.launch(replacement) {} }
            assertThrows(IllegalArgumentException::class.java) {
                runtime.launch(start = CoroutineStart.UNDISPATCHED) {}
            }
        } finally {
            replacement.cancel()
            root.close()
        }
    }

    @Test
    fun `IO work resumes on the main queue before touching server state`() {
        val queue = MainQueue()
        val root = newScope(queue)
        val runtime = root.coroutines
        val mainThread = Thread.currentThread()
        val entered = CountDownLatch(1)
        val release = CompletableDeferred<Unit>()
        var applied = false
        val task = runtime.launch {
            assertSame(mainThread, Thread.currentThread())
            val answer = withContext(Dispatchers.Default) {
                assertNotSame(mainThread, Thread.currentThread())
                entered.countDown()
                release.await()
                42
            }
            assertSame(mainThread, Thread.currentThread())
            assertEquals(42, answer)
            applied = true
        }
        queue.drain()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        release.complete(Unit)
        queue.runNext()
        assertTrue(applied)
        assertTrue(task.isCompleted)
        root.close()
    }

    @Test
    fun `explicit cancellation and lazy close both terminate without running later effects`() {
        val queue = MainQueue()
        val root = newScope(queue)
        var cleanup = false
        val task = root.coroutines.launch {
            try {
                delay(Long.MAX_VALUE)
                fail<Unit>("cancelled continuation ran")
            } finally {
                cleanup = true
            }
        }
        queue.drain()
        task.cancel()
        queue.drain()
        assertTrue(cleanup)
        assertTrue(task.isCompleted)
        val lazy = root.coroutines.launch(start = CoroutineStart.LAZY) { fail<Unit>("lazy body ran") }
        root.close()
        awaitCompletion(lazy)
        assertTrue(lazy.isCancelled)
    }

    @Test
    fun `close does not join cleanup running off the main thread`() {
        val queue = MainQueue()
        val root = newScope(queue)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val task = root.coroutines.launch {
            try {
                awaitCancellation()
            } finally {
                entered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
            }
        }
        queue.drain()
        try {
            root.close()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertFalse(task.isCompleted, "close waited for cancellation cleanup")
        } finally {
            release.countDown()
        }
        awaitCompletion(task)
    }

    @Test
    fun `concurrent close rebuild and submission never strand or leak old Jobs`() {
        val queue = MainQueue()
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(50) { iteration ->
                val root = newScope(queue)
                val runtime = root.coroutines
                val gate = CountDownLatch(1)
                val submitted = executor.submit<Job> {
                    gate.await()
                    runtime.launch { fail("old queued body must not run") }
                }
                val rebuilt = executor.submit {
                    gate.await()
                    if (iteration % 2 == 0) root.close() else root.rebuild()
                }
                gate.countDown()
                val task = submitted.get(5, TimeUnit.SECONDS)
                rebuilt.get(5, TimeUnit.SECONDS)
                root.close()
                awaitCompletion(task)
                queue.drain()
                assertTrue(task.isCancelled)
                assertTrue(runtime.job.isCancelled)
            }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun newScope(queue: MainQueue): ScopeImpl = scope("test") {
        registerCapability<SchedulerFactory>(queue)
    }

    private fun awaitCompletion(job: Job) {
        val completed = CountDownLatch(1)
        job.invokeOnCompletion { completed.countDown() }
        assertTrue(completed.await(5, TimeUnit.SECONDS), "Coroutine did not terminate after cancellation")
        assertTrue(job.isCompleted)
    }

    private class MainQueue : SchedulerFactory {
        private val tasks = LinkedBlockingQueue<Runnable>()
        @Volatile var rejected = false
        @Volatile var failCancellation = false
        fun hasTasks(): Boolean = tasks.isNotEmpty()
        fun runNext() {
            val task = tasks.poll(5, TimeUnit.SECONDS)
            assertNotNull(task, "Expected a continuation on the main queue")
            task!!.run()
        }
        fun drain() {
            while (true) (tasks.poll() ?: return).run()
        }
        override fun forScope(scope: Scope): KScheduler = object : KScheduler {
            override fun sync(task: Runnable): TaskHandle {
                if (rejected) throw RejectedExecutionException("test queue stopped")
                val handle = scope.install(Handle(scope, failCancellation))
                tasks.add {
                    try {
                        if (!handle.isCancelled) task.run()
                    } finally {
                        handle.done = true
                        scope.remove(handle)
                    }
                }
                return handle
            }
            override fun after(delay: Ticks, task: Runnable): TaskHandle = sync(task)
            override fun every(period: Ticks, task: Runnable): TaskHandle = error("not used")
            override fun <T : Any?> async(supplier: Supplier<T>): AsyncTask<T> = error("not used")
        }
        private class Handle(private val owner: Scope, private val failCancellation: Boolean) : TaskHandle {
            @Volatile private var cancelled = false
            @Volatile var done = false
            override fun cancel(): Boolean {
                if (cancelled) return false
                cancelled = true
                owner.remove(this)
                if (failCancellation) throw IllegalStateException("queue handle cancellation failed")
                return true
            }
            override fun isCancelled(): Boolean = cancelled
            override fun isDone(): Boolean = done || cancelled
        }
    }
}
