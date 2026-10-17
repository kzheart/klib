package me.kzheart.klib.remote

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class KotlinRemoteConsumerTest {
    @Test fun javaSpiKeepsNullableImplementationAndKotlinFactories() {
        val contributor = object : DiagnosticContributor {
            override fun name(): String? = "kotlin"
            override fun contribute(context: DiagnosticContributor.Context?): Map<String, *>? = context?.let { emptyMap<String, Any?>() }
        }
        assertNull(contributor.contribute(null))
        val nested = arrayListOf<Any?>("before", null)
        val event = RemoteEvent.of("custom", mapOf("nested" to nested))
        nested[0] = "after"
        assertEquals(listOf("before", null), event.toMap()["nested"])
    }

    // Compiles both forms previously exposed by the Java bean method.
    private fun activity(delivery: RemoteDelivery): Boolean = delivery.isActive && delivery.isActive()
}
