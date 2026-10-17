package me.kzheart.klib.hook.papi;

import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RemainingPlatformReturnJavaTest {
    @Test void resolutionIsDeclaredOnThePublicClassAndReturnsRealNull() throws Exception {
        PapiExpansion expansion = new PapiDsl().key("answer", player -> 42).build();
        assertNull(expansion.resolve(null, "missing"));
        assertEquals("42", expansion.resolve(null, "answer"));
        assertEquals(String.class, PapiExpansion.class.getDeclaredMethod("resolve", OfflinePlayer.class, String.class).getReturnType());
        assertThrows(NullPointerException.class, () -> expansion.resolve(null, null));
    }
}
