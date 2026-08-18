package me.kzheart.klib.ui;

import me.kzheart.klib.ui.drop.DropResult;
import me.kzheart.klib.ui.drop.DropZoneController;
import me.kzheart.klib.ui.drop.InventoryAction;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DropZoneShiftSplitTest {
    @Test
    void shiftInsertSplitsWithoutMutatingTheSource() {
        DropZoneController zone = new DropZoneController(
                new LinkedHashSet<Integer>(Arrays.asList(Integer.valueOf(10), Integer.valueOf(11))),
                item -> item.getType() == Material.STONE);
        ItemStack source = new ItemStack(Material.STONE, 100);

        DropResult result = zone.handle(InventoryAction.shiftInsert(source));

        assertEquals(100, result.accepted());
        assertEquals(100, source.getAmount());
        Map<Integer, ItemStack> contents = zone.snapshot();
        assertEquals(64, contents.get(Integer.valueOf(10)).getAmount());
        assertEquals(36, contents.get(Integer.valueOf(11)).getAmount());

        DropResult taken = zone.handle(InventoryAction.take(10, 7));
        assertEquals(7, taken.removed().orElseThrow(AssertionError::new).getAmount());
        assertEquals(57, zone.snapshot().get(Integer.valueOf(10)).getAmount());
    }

    @Test
    void invalidDragIsRejectedBeforeChangingAnySlot() {
        DropZoneController zone = new DropZoneController(
                new LinkedHashSet<Integer>(Arrays.asList(Integer.valueOf(1), Integer.valueOf(2))),
                item -> true);
        ItemStack source = new ItemStack(Material.STONE, 5);

        assertThrows(IllegalArgumentException.class, () -> zone.handle(InventoryAction.drag(
                source,
                java.util.Collections.singletonMap(Integer.valueOf(1), Integer.valueOf(6)))));
        assertEquals(0, zone.snapshot().size());
    }
}
