package me.kzheart.klib.lang

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LanguageSourceCompatibilityTest {
    @Test fun `boolean accessors retain method and property forms`() {
        assertTrue(MessageColor.RED.isLegacy)
        assertTrue(MessageColor.RED.isLegacy())
        val recipient = MessageRecipient.of(Any(), true)
        assertTrue(recipient.isConsole)
        assertTrue(recipient.isConsole())
    }

    @Test fun `optional style methods keep the Java platform return contract`() {
        val action = TextAction(TextAction.Type.RUN_COMMAND, "/test")
        val styled = RichTextSegment("styled", MessageColor.RED, true, action, action)
        val color: MessageColor = styled.color()
        val hover: TextAction = styled.hover()
        val click: TextAction = styled.click()
        assertSame(MessageColor.RED, color)
        assertSame(action, hover)
        assertSame(action, click)
        assertEquals(MessageColor.RED.rgb(), if (styled.color() != null) styled.color().rgb() else 0)
        assertEquals("/test", if (styled.hover() != null) styled.hover().value() else "")
        assertEquals("/test", if (styled.click() != null) styled.click().value() else "")
        val plain = RichTextSegment.plain("plain")
        assertNull(plain.color())
        assertNull(plain.hover())
        assertNull(plain.click())
        val replacement = styled.withText("replacement")
        assertEquals("replacement", replacement.text())
        assertSame(styled.color(), replacement.color())
        assertSame(styled.hover(), replacement.hover())
        assertSame(styled.click(), replacement.click())
        assertEquals("styled", styled.text())
    }
}
