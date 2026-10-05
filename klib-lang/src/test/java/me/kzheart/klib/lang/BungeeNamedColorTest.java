package me.kzheart.klib.lang;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BungeeNamedColorTest {
    public static final class ModernColor {
        public static final ModernColor GOLD = new ModernColor();
    }
    @Test void modernColorConstantsDoNotRequireAnEnumClass() throws ReflectiveOperationException {
        assertFalse(ModernColor.class.isEnum());
        assertSame(ModernColor.GOLD, ReflectionBukkitComponentSender.namedColor(ModernColor.class, MessageColor.GOLD));
    }
}
