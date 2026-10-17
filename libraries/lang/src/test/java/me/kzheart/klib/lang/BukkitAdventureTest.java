package me.kzheart.klib.lang;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class BukkitAdventureTest {
    @Test void hostGenericSerializerMethodsAreResolvedWithoutShadedDescriptors() {
        assertTrue(BukkitAdventure.available());
        Object component=BukkitAdventure.parse("<red>你好</red>");
        assertEquals("你好",BukkitAdventure.plain(component));
        assertEquals("<red>你好",BukkitAdventure.miniMessage(component));
        assertEquals("§c你好",BukkitAdventure.legacy("<red>你好</red>"));
        assertEquals("旧颜色",BukkitAdventure.plain(BukkitAdventure.parse("&a旧颜色")));
    }
    @Test void richFontAndHoverSurviveRoundTrip() {
        String text="<font:custom:offset><hover:show_text:'说明'>字</hover></font>";
        assertTrue(BukkitAdventure.miniMessage(BukkitAdventure.parse(text)).contains("custom:offset"));
        assertTrue(BukkitAdventure.miniMessage(BukkitAdventure.parse(text)).contains("hover"));
    }
}
