package me.kzheart.klib.item;

import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemBuilderMetaTest {
    @Test
    void modernServerWritesCustomModelDataAndNativeGlint() {
        FakeItemServer.install(true);
        ItemStack item = Items.of(Material.DIAMOND_SWORD)
                .customModelData(1001)
                .glow(true)
                .unbreakable(true)
                .build();

        assertEquals(Integer.valueOf(1001), Items.customModelData(item));
        ItemMeta meta = item.getItemMeta();
        assertEquals(Boolean.TRUE, FakeItemServer.stateOf(meta, "glint"));
        assertFalse(meta.hasEnchant(Enchantment.LURE));
        assertTrue(meta.isUnbreakable());

        ItemStack cleared = Items.edit(item).customModelData(null).glow(false).build();
        assertNull(Items.customModelData(cleared));
        assertNull(FakeItemServer.stateOf(cleared.getItemMeta(), "glint"));
    }

    @Test
    void legacyServerIgnoresCustomModelDataAndFakesGlint() {
        FakeItemServer.install(false);
        ItemStack item = Items.of(Material.DIAMOND_SWORD).customModelData(7).glow(true).build();

        assertNull(Items.customModelData(item));
        ItemMeta meta = item.getItemMeta();
        assertEquals(1, meta.getEnchantLevel(Enchantment.LURE));
        assertTrue(meta.hasItemFlag(ItemFlag.HIDE_ENCHANTS));

        ItemMeta off = Items.edit(item).glow(false).build().getItemMeta();
        assertFalse(off.hasEnchant(Enchantment.LURE));
        assertFalse(off.hasItemFlag(ItemFlag.HIDE_ENCHANTS));
    }

    @Test
    void playerHeadUsesLegacySkullOnOneTwelveAndAcceptsOwner() {
        FakeItemServer.install(false);
        UUID owner = UUID.randomUUID();
        ItemStack head = Items.playerHead().skullOwner(owner).name("&b头像").build();

        assertEquals(FakeItemServer.material("SKULL_ITEM"), head.getType());
        assertEquals(3, head.getDurability());
        assertEquals(owner, FakeItemServer.stateOf(head.getItemMeta(), "owner"));
    }

    @Test
    void skullOwnerRejectsNonSkullItems() {
        FakeItemServer.install(false);
        assertThrows(IllegalArgumentException.class,
                () -> Items.of(Material.DIAMOND).skullOwner(UUID.randomUUID()));
    }
}
