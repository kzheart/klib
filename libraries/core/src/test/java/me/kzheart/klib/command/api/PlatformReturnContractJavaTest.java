package me.kzheart.klib.command.api;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlatformReturnContractJavaTest {
    @Test void classicTemplatesRemainNullableAndBuilderKeepsItsSnapshot() {
        CommandHelpStyle style = CommandHelpStyle.preset(CommandHelpStyle.Preset.CLASSIC);
        assertNull(style.header());
        assertNull(style.previous());
        assertNull(style.next());
        CommandHelpStyle.Builder builder = style.toBuilder().header("first");
        CommandHelpStyle first = builder.build();
        builder.header("second");
        assertEquals("first", first.header());
        assertNull(style.header());
    }
}
