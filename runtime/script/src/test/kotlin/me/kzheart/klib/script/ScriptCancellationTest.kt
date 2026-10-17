package me.kzheart.klib.script

import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.kzheart.klib.scope.ScopeImpl
import me.kzheart.klib.script.kether.core.QuestAction
import me.kzheart.klib.script.kether.core.QuestActionParser
import me.kzheart.klib.script.kether.core.QuestContext
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

@Timeout(10)
class ScriptCancellationTest {
    @Test
    fun `default timeout cancellation cleans actual native frame on timeout thread`() = runBlocking {
        val host = HostQueue()
        val native = NativeExecution(host)
        try {
            val task = launch(host) {
                withTimeout(250) { native.runtime.evalAwait("gate", native.context) }
            }
            host.runNext()
            val cleanupThread = native.closedOn.get(5, TimeUnit.SECONDS)
            assertNotSame(Thread.currentThread(), cleanupThread)
            assertTrue(native.gate.isCancelled)
            while (!task.isCompleted) host.runNext()
            host.drain()
            task.join()
            assertEquals(1, native.closeCount.get())
        } finally {
            native.scope.close()
        }
    }

    @Test
    fun `explicit executor moves timeout native frame cleanup to host thread`() = runBlocking {
        val host = HostQueue()
        val native = NativeExecution(host)
        try {
            val task = launch(host) {
                withTimeout(250) { native.runtime.evalAwait("gate", native.context, host) }
            }
            host.runNext()
            while (!native.closedOn.isDone) host.runNext()
            while (!task.isCompleted) host.runNext()
            host.drain()
            task.join()
            assertSame(Thread.currentThread(), native.closedOn.get())
            assertTrue(native.gate.isCancelled)
            assertEquals(1, native.closeCount.get())
        } finally {
            native.scope.close()
        }
    }

    @Test
    fun `condition background cancellation queues actual native cleanup on host executor`() = runBlocking {
        val host = HostQueue()
        val native = NativeExecution(host)
        try {
            val task = launch(host) {
                native.runtime.evalConditionAwait("gate", native.context, host)
            }
            host.runNext()
            val cancellationThread = Thread({ task.cancel() }, "script-cancel-worker")
            cancellationThread.start()
            cancellationThread.join(5000)
            assertFalse(cancellationThread.isAlive)
            assertFalse(native.closedOn.isDone, "cleanup ran on the cancelling thread")
            while (!task.isCompleted) host.runNext()
            host.drain()
            task.join()
            assertSame(Thread.currentThread(), native.closedOn.get(5, TimeUnit.SECONDS))
            assertTrue(native.gate.isCancelled)
            assertEquals(1, native.closeCount.get())
        } finally {
            native.scope.close()
        }
    }

    @Test
    fun `rejected cancellation reports failure without wrong thread cleanup and owner still closes`() = runBlocking {
        val host = HostQueue()
        val native = NativeExecution(host)
        val rejection = RejectedExecutionException("cleanup executor stopped")
        val reported = CompletableFuture<Throwable>()
        val handler = CoroutineExceptionHandler { _, failure -> reported.complete(failure) }
        try {
            val task = launch(host + handler) {
                native.runtime.evalAwait("gate", native.context, Executor { throw rejection })
            }
            host.runNext()
            val cancellationThread = Thread({ task.cancel() }, "script-cancel-worker")
            cancellationThread.start()
            cancellationThread.join(5000)
            assertFalse(cancellationThread.isAlive)
            assertSame(rejection, reported.get(5, TimeUnit.SECONDS).cause)
            assertFalse(native.closedOn.isDone)
            assertFalse(native.gate.isCancelled)
            while (!task.isCompleted) host.runNext()
            host.drain()
            task.join()
            native.scope.close()
            assertSame(Thread.currentThread(), native.closedOn.get(5, TimeUnit.SECONDS))
            assertTrue(native.gate.isCancelled)
            assertEquals(1, native.closeCount.get())
        } finally {
            native.scope.close()
        }
    }

    @Test
    fun `explicit cancellation executor is unused for ordinary values and failures`() = runBlocking {
        val host = HostQueue()
        val native = NativeExecution(host)
        val neverUsed = Executor { fail<Unit>("successful completion requested cancellation") }
        try {
            assertEquals("hello", native.runtime.evalAwait("literal hello", native.context, neverUsed))
            assertTrue(native.runtime.evalConditionAwait("gte 5 3", native.context, neverUsed))
            val failure = assertThrows(ScriptException::class.java) {
                runBlocking { native.runtime.evalAwait("missing_action", native.context, neverUsed) }
            }
            assertTrue(failure.message!!.contains("missing_action"))
        } finally {
            native.scope.close()
        }
    }

    private class NativeExecution(host: Executor) {
        val scope = ScopeImpl("script-cancellation")
        val gate = CompletableFuture<Any?>()
        val closedOn = CompletableFuture<Thread>()
        val closeCount = AtomicInteger()
        val context = scriptContext {}
        val runtime: ScopedScriptRuntime

        init {
            val registry = StatementRegistry()
            registry.registerKether(scope, "klib", "gate", QuestActionParser.of {
                object : QuestAction<Any?>() {
                    override fun process(frame: QuestContext.Frame): CompletableFuture<Any?> {
                        frame.addClosable(AutoCloseable {
                            closeCount.incrementAndGet()
                            closedOn.complete(Thread.currentThread())
                        })
                        return gate
                    }
                }
            })
            runtime = ScopedScriptRuntime(scope, registry, null, host, false)
            runtime.validate("gate", context)
        }
    }

    private class HostQueue : CoroutineDispatcher(), Executor {
        private val tasks = LinkedBlockingQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) = execute(block)
        override fun execute(command: Runnable) { tasks.add(command) }
        fun runNext() {
            val task = tasks.poll(5, TimeUnit.SECONDS)
            assertNotNull(task, "expected host task")
            task!!.run()
        }
        fun drain() {
            while (true) (tasks.poll() ?: return).run()
        }
    }
}
