package me.kzheart.klib.item;

import me.kzheart.klib.item.tag.NativeItemTags;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LegacyNbtPlatformSupportTest {
    @Test
    void apiPresenceWithoutACraftServerDoesNotClaimNativeSupport() {
        assertFalse(new LegacyNbtItemTagBridge().supports());
        assertFalse(NativeItemTags.supports());
    }

    @Test
    void unavailableNativeLayoutFailsExplicitlyOnRealItemAccess() {
        assertThrows(IllegalStateException.class,
                () -> NativeItemTags.read(new ItemStack(Material.STONE)));
    }
}
