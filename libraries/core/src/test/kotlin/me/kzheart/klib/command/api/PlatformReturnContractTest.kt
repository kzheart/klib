package me.kzheart.klib.command.api

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PlatformReturnContractTest {
    // This old Java-platform consumer must compile without adding !! or safe calls.
    private fun textSize(style: CommandHelpStyle): Int =
        (if (style.header() != null) style.header().length else 0) +
        (if (style.previous() != null) style.previous().length else 0) +
        (if (style.next() != null) style.next().length else 0)

    @Test fun `classic nullable templates remain real nulls through Java platform access`() {
        val style = CommandHelpStyle.preset(CommandHelpStyle.Preset.CLASSIC)
        assertNull(style.header())
        assertNull(style.previous())
        assertNull(style.next())
        assertEquals(0, textSize(style))
        val changed = style.toBuilder().header("heading").previous("back").next("forward").build()
        assertEquals(18, textSize(changed))
        assertNull(style.header())
    }
}
