package me.kzheart.klib.script.kether.core

import java.util.concurrent.CompletableFuture
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** 源码编译本身也是测试：这些写法依赖 Java 平台空性和 synthetic properties。 */
class KetherCoreInteropTest {
    @Test
    fun `open reader contract accepts nullable and non-null Kotlin overrides`() {
        SimpleQuestService().use { service ->
            val blocks = BlockReader("*value".toCharArray(), service, emptyList())
            val nullable = object : SimpleReader(service, blocks, emptyList()) {
                override fun nextToken(): String? = null
                override fun getService(): QuestService<*>? = null
            }
            val nonNull = object : SimpleReader(service, blocks, emptyList()) {
                override fun nextToken(): String = "token"
                override fun getService(): QuestService<*> = service
            }
            assertNull(nullable.nextToken())
            assertNull(nullable.getService())
            // 同包 protected 字段应继续直接读取，不能意外改成 getter 分派。
            assertSame(service, nullable.service)
            assertEquals("token", nonNull.nextToken())
            assertSame(service, nonNull.service)
        }
    }

    @Test
    fun `Java getters remain callable as methods and properties`() {
        val nullableInt: ArgType<Int?> = ArgTypes.INT
        val nullableFrame: ParsedAction.ActionProperty<Boolean?> = ActionProperties.REQUIRE_FRAME
        assertSame(ArgTypes.INT, nullableInt)
        assertSame(ActionProperties.REQUIRE_FRAME, nullableFrame)
        val registry = DefaultRegistry()
        assertEquals(registry.getRegisteredNamespace(), registry.registeredNamespace)
        assertEquals(registry.getRegisteredActions(), registry.registeredActions)
        val token = TokenBlock("value", true)
        assertEquals(token.getToken(), token.token)
        assertEquals(token.isBlock(), token.isBlock)
        val action = ParsedAction.noop<Any?>()
        assertSame(action.getAction(), action.action)
        assertSame(action.getProperties(), action.properties)
        val future = QuestFuture(action)
        assertSame(future.getAction(), future.action)
        assertNull(future.getFuture())
        assertNull(future.future)
        val error = KetherLexException.endOfInput(3)
        assertEquals(error.getPosition(), error.position)
        val status = ExitStatus.success()
        assertEquals(status.getStartTime(), status.startTime)
        assertEquals(status.isRunning(), status.isRunning)
    }

    @Test
    fun `generic action SPI keeps nullable frame and return implementations`() {
        val nullable = object : Parser.Action<String> {
            override fun run(frame: QuestContext.Frame?): CompletableFuture<String>? = null
        }
        val nonNull = object : Parser.Action<String> {
            override fun run(frame: QuestContext.Frame): CompletableFuture<String> = CompletableFuture.completedFuture("ok")
        }
        assertNull(nullable.run(null))
        SimpleQuestService(true).use { service ->
            val frame = service.newContext(service.load("interop", "def main = { *value }")).rootFrame()
            assertEquals("ok", nonNull.run(frame).join())
        }
    }

    @Test
    fun `shared scanner preserves cursor on unclosed single and multiple quotes`() {
        val single = KetherTokenReader("'open")
        assertThrows(KetherLexException::class.java) { single.nextTokenBlock() }
        assertEquals(5, single.index)
        val multiple = KetherTokenReader("\"\"\"open")
        assertThrows(KetherLexException::class.java) { multiple.nextTokenBlock() }
        assertEquals(3, multiple.index)
        SimpleQuestService(true).use { service ->
            val blocks = BlockReader("\"\"\"line one\nline two\"\"\"".toCharArray(), service, emptyList())
            val reader = SimpleReader(service, blocks, emptyList())
            assertEquals("line one\nline two", reader.nextTokenBlock().token)
            assertFalse(reader.hasNext())
        }
    }
}
