package me.kzheart.klib.script
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
class ScriptCoroutinesTest {
    @Test fun `same Kether engine evaluates native Kotlin calls`() = runBlocking {
        val engine = KetherScriptEngine(StatementRegistry())
        val context = scriptContext { variable("product", "Klib") }
        assertEquals("hello-Klib", engine.evalAwait("inline hello-\${product}", context))
        assertTrue(engine.evalConditionAwait("gte 5 3", context))
    }
    @Test fun `cancellation reaches existing future`() = runBlocking {
        val future = CompletableFuture<Any>()
        val engine = object : ScriptEngine {
            override fun eval(script: String, context: ScriptContext) = future
            override fun evalCondition(script: String, context: ScriptContext) = CompletableFuture.completedFuture(true)
        }
        val job = launch(start = CoroutineStart.UNDISPATCHED) { engine.evalAwait("wait", scriptContext {}) }
        job.cancelAndJoin()
        assertTrue(future.isCancelled)
    }
}
