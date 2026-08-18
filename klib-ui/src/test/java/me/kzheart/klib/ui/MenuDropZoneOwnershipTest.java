package me.kzheart.klib.ui;

import me.kzheart.klib.scope.ScopeImpl;
import me.kzheart.klib.ui.drop.DropZoneController;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertThrows;

class MenuDropZoneOwnershipTest {
    @Test
    void rejectsOutOfBoundsOverlappingAndEntrySlots() {
        ScopeImpl parent = new ScopeImpl("test");
        MenuModel model = MenuCompiler.compile(MenuTemplate.builder("Zones", 1)
                .slot(1, MenuEntry.of(new ItemStack(Material.STONE)))
                .build());
        MenuSession session = MenuSession.open(
                parent,
                "zones",
                model,
                items -> Collections.emptyList(),
                items -> items);

        assertThrows(IllegalArgumentException.class, () -> session.addDropZone(zone(9)));
        assertThrows(IllegalArgumentException.class, () -> session.addDropZone(zone(1)));
        session.addDropZone(zone(2));
        assertThrows(IllegalArgumentException.class, () -> session.addDropZone(zone(2)));
        parent.close();
    }

    private static DropZoneController zone(int slot) {
        return new DropZoneController(Collections.singleton(Integer.valueOf(slot)), item -> true);
    }
}
