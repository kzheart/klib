package me.kzheart.klib.script

import me.kzheart.klib.scope.ScopeImpl
import me.kzheart.klib.script.taboolib.common.OpenResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

class KotlinImplementationConsumerTest {
    @Test fun nullableJavaSpiImplementationsRemainNatural() {
        val resolver = object : UnknownStatementResolver {
            override fun resolve(statement: String?, context: ScriptContext?): CompletionStage<Any?>? =
                CompletableFuture.completedFuture<Any?>(statement)
        }
        assertNull(resolver.resolve(null, null)!!.toCompletableFuture().join())
        val containers = object : TabooLibKetherInterop.ContainerDiscovery {
            override fun discover(): List<TabooLibKetherInterop.OpenContainer>? = null
            override fun find(name: String?): TabooLibKetherInterop.OpenContainer? = null
        }
        assertNull(containers.discover())
    }

    @Test fun sourceAndMethodBeanFormsRemainCallable() {
        val result = OpenResult.successful("value")
        assertTrue(result.isSuccessful)
        assertTrue(result.isSuccessful())
        assertEquals(result.value, result.getValue())
    }

    @Test fun unchangedHostValuesDoNotInvokeUserEquality() {
        val value = object {
            override fun equals(other: Any?): Boolean = throw AssertionError("Host equality must not run for the same reference")
            override fun hashCode(): Int = System.identityHashCode(this)
        }
        val context = ScriptContext.builder().variable("opaque", value).build()
        val engine = KetherScriptEngine(StatementRegistry())
        assertEquals("ok", engine.eval("literal ok", context).toCompletableFuture().join())
        assertSame(value, context.variableOrNull("opaque"))
    }

    @Test fun kotlinConsumerRunsScopedCachedImplementation() {
        val scope = ScopeImpl("kotlin-script-consumer")
        try {
            val registry = StatementRegistry()
            registry.register(scope, "greet", QuestActionParser { _, _ -> CompletableFuture.completedFuture("hello") })
            val engine = KetherScriptEngine(registry)
            val context = ScriptContext.builder().build()
            assertEquals("hello", engine.evalChecked("greet", context).toCompletableFuture().join())
            assertEquals("hello", engine.eval("greet", context).toCompletableFuture().join())
            assertEquals(1L, engine.compilationCount())
        } finally { scope.close() }
    }
}
