package me.kzheart.klib.lang

import java.util.Optional
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LanguageImplementationInteropTest {
    @Test fun `Kotlin implements the public catalog SAM and consumes literal placeholders`() {
        val catalog = MessageCatalog { key -> if (key == "hello") Optional.of("<green>Hello {name}</green>") else Optional.empty() }
        var routed: RichText? = null
        val pipeline = MessagePipeline(catalog, "", null, MessageRouter { _, message -> routed = message })
        val result = pipeline.send(MessageRecipient.of(Any(), false), "hello", Placeholders.of("name", "<red>literal</red>"))
        assertSame(result, routed)
        assertEquals("Hello <red>literal</red>", result.plainText())
        assertTrue(result.segments().all { it.color() == MessageColor.GREEN })
    }

    @Test fun `Kotlin catalog adapters preserve source precedence across value shapes`() {
        val primary = MapMessageCatalog(mapOf("key" to listOf("first", "second")))
        val fallback = MapMessageCatalog(mapOf("key" to RichText.plain("fallback")))
        val catalog = FallbackMessageCatalog(primary, fallback)
        assertEquals(listOf("first", "second"), catalog.findAny("key").get())
        val live = ReloadableMessageCatalog(catalog)
        live.replace(MapMessageCatalog(mapOf("key" to "replacement")))
        assertEquals("replacement", live.find("key").get())
    }

    @Test fun `Java platform SPI still permits nullable Kotlin implementer contracts`() {
        val optionalStage = object : PlaceholderApi {
            override fun expand(recipient: MessageRecipient?, text: String?): String? = text
        }
        assertNull(optionalStage.expand(null, null))
    }
}
