package me.kzheart.klib.scope

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class OperationObservationTest {
    @Test fun `observer cancellation leaves accepted source and other observers alive`() = runBlocking {
        val source = CompletableFuture<Int>()
        val first = async(start = CoroutineStart.UNDISPATCHED) { source.observeOperation() }
        val second = async(start = CoroutineStart.UNDISPATCHED) { source.observeOperation() }
        first.cancelAndJoin()
        assertFalse(source.isDone)
        source.complete(42)
        assertEquals(42, second.await())
    }

    @Test fun `provider cancellation is an unknown failure and caller stays active`() = runBlocking {
        for (completedFirst in listOf(false, true)) {
            val source = CompletableFuture<Int>()
            if (completedFirst) source.cancel(false)
            val observer = async(start = CoroutineStart.UNDISPATCHED) {
                try {
                    source.observeOperation()
                    fail<Unit>("cancelled provider returned a value")
                } catch (failure: SourceOperationCancelledException) {
                    assertInstanceOf(java.util.concurrent.CancellationException::class.java, failure.cause)
                }
                17
            }
            if (!completedFirst) source.cancel(false)
            assertEquals(17, observer.await())
        }
    }

    @Test fun `transport wrapped cancellation is also an unknown failure`() = runBlocking {
        val source = CompletableFuture<Int>()
        source.completeExceptionally(CompletionException(java.util.concurrent.CancellationException("provider")))
        try {
            source.observeOperation()
            fail<Unit>("cancelled provider returned a value")
        } catch (failure: SourceOperationCancelledException) {
            assertEquals("provider", failure.cause?.message)
        }
    }

    @Test fun `ordinary failure and nullable success retain their facts`() = runBlocking {
        val failure = IllegalArgumentException("provider failure")
        val source = CompletableFuture<Int>().apply { completeExceptionally(failure) }
        try {
            source.observeOperation()
            fail<Unit>("failed provider returned a value")
        } catch (actual: IllegalArgumentException) {
            assertEquals(failure.message, actual.message)
        }
        assertNull(CompletableFuture.completedFuture<String?>(null).observeOperation())
    }
}
