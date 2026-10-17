package me.kzheart.klib.command;

import java.lang.reflect.Modifier;
import me.kzheart.klib.lang.RichText;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RemainingPlatformReturnJavaTest {
    @Test void declarationsRemainOnTheirPublicTypes() throws Exception {
        assertEquals(Player.class, BukkitPlayerResolver.class.getDeclaredMethod("findExact", String.class).getReturnType());
        assertEquals(RichText.class, CommandResult.class.getDeclaredMethod("message").getReturnType());
        assertFalse(Modifier.isFinal(CommandResult.class.getDeclaredMethod("success").getModifiers()));
        assertNull(CommandResult.success().message());
        assertNull(CommandResult.message(null, null).status());
        assertSame(CommandResult.Status.SUCCESS, CommandResult.success().status());
    }
}
