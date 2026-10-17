package me.kzheart.klib.item;

import me.kzheart.klib.item.tag.NativeItemTags;
import me.kzheart.klib.item.tag.NbtCompound;
import me.kzheart.klib.item.tag.NbtTag;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Java consumer contract. Real server codec and component round trips are separate integration tests. */
class NativeCompleteItemContractTest {
    @Test
    void completeItemRequiresStringIdentityBeforeLookingUpServer() {
        assertThrows(IllegalArgumentException.class, () -> NativeItemTags.createItem(new NbtCompound()));
        assertThrows(IllegalArgumentException.class, () -> NativeItemTags.createItem(
                new NbtCompound().put("id", NbtTag.intValue(1))));
        assertThrows(IllegalArgumentException.class, () -> NativeItemTags.createItem(
                new NbtCompound().put("id", NbtTag.string(""))));
    }

    @Test
    void completeSnapshotRejectsAirRatherThanTreatingItAsCustomData() {
        assertThrows(IllegalArgumentException.class, () -> NativeItemTags.readItem(new ItemStack(Material.AIR)));
    }

    @Test
    void unsupportedServerFailsWithoutChangingInputTreeOrItem() {
        NbtCompound tree = new NbtCompound().put("id", NbtTag.string("minecraft:stone"));
        NbtCompound before = tree.copy();
        assertThrows(IllegalStateException.class, () -> NativeItemTags.createItem(tree));
        assertEquals(before, tree);
        ItemStack item = new ItemStack(Material.STONE, 3);
        assertThrows(IllegalStateException.class, () -> NativeItemTags.readItem(item));
        assertEquals(Material.STONE, item.getType());
        assertEquals(3, item.getAmount());
    }
}
