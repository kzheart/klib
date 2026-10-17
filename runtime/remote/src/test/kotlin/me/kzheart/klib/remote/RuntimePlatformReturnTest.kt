package me.kzheart.klib.remote

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RuntimePlatformReturnTest {
    @Test fun `operation snapshots keep platform assignments and missing parent phase`() {
        assertNull(RemoteOperation.current())
        RemoteOperation.start("outer").use { outer ->
            val current: RemoteOperation.Context = RemoteOperation.current()
            assertSame(outer.context(), current)
            assertNull(current.parentId())
            assertNull(current.phase())
            RemoteOperation.start("inner").phase("execute").use { inner ->
                val parentId: String = inner.context().parentId()
                val phase: String = inner.context().phase()
                assertEquals(current.id(), parentId)
                assertEquals("execute", phase)
                assertEquals(7, if (inner.context().phase() != null) inner.context().phase().length else 0)
            }
            assertSame(current, RemoteOperation.current())
        }
        assertNull(RemoteOperation.current())
    }

    @Test fun `receipt error and initial settings retain actual null values`() {
        val accepted = RemoteBatchResult.EventResult(0, "event", RemoteBatchResult.Status.ACCEPTED, null)
        val rejected = RemoteBatchResult.EventResult(1, "event2", RemoteBatchResult.Status.REJECTED, "invalid")
        assertNull(accepted.error())
        val error: String = rejected.error()
        assertEquals("invalid", error)
        assertEquals(7, if (rejected.error() != null) rejected.error().length else 0)
    }

    // A refreshed client guarantees settings; initial state remains nullable at runtime.
    private fun refreshedSettings(client: RemoteClient): RemoteSettings = client.settings()
}
