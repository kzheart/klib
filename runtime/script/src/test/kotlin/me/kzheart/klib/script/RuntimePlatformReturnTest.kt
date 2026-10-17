package me.kzheart.klib.script

import java.util.concurrent.CompletableFuture
import me.kzheart.klib.script.taboolib.common.OpenResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RuntimePlatformReturnTest {
    @Test fun `context preserves direct platform assignments and actual missing values`() {
        val context = ScriptContext.builder().variable("value", "stored").build()
        val stored: Any = context.variableOrNull("value")
        assertEquals("stored", stored)
        val removed: Any = context.removeVariable("value")
        assertEquals("stored", removed)
        assertNull(context.variableOrNull("value"))
        assertNull(context.removeVariable("value"))
    }

    @Test fun `statement values and protocol getters preserve nullable platform contract`() {
        val context = ScriptContext.builder().variable("value", "stored").build()
        val arguments = StatementArguments(mapOf("value" to "&value"))
        val argument: Any = arguments.value("value", context)
        assertEquals("stored", argument)
        val call = StatementCall("test", listOf("&value"), 1, 1) { source, _ ->
            CompletableFuture.completedFuture(source)
        }
        val called: Any = call.value(0, context)
        assertEquals("stored", called)
        context.removeVariable("value")
        assertNull(arguments.value("value", context))
        assertNull(call.value(0, context))
        val result = OpenResult.successful("value")
        val property: Any = result.value
        val method: Any = result.getValue()
        assertEquals("value", property)
        assertEquals("value", method)
        assertTrue(result.isSuccessful)
        assertTrue(result.isSuccessful())
        assertNull(OpenResult.failed().value)
        assertNull(OpenResult.failed().getValue())
    }
}
