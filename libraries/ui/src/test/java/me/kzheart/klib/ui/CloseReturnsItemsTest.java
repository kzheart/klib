package me.kzheart.klib.ui;

import me.kzheart.klib.scope.ScopeImpl;
import me.kzheart.klib.ui.drop.DropZoneController;
import me.kzheart.klib.ui.drop.InventoryAction;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloseReturnsItemsTest {
    @Test
    void closingReturnsOwnedItemsExactlyOnce() {
        ScopeImpl parent = new ScopeImpl("test");
        AtomicInteger returned = new AtomicInteger();
        MenuModel model = MenuCompiler.compile(MenuTemplate.builder("Stall", 1).build());
        MenuSession menu = MenuSession.open(
                parent,
                "stall",
                model,
                items -> {
                    for (ItemStack item : items) {
                        returned.addAndGet(item.getAmount());
                    }
                    return Collections.emptyList();
                },
                items -> items);
        DropZoneController zone = menu.addDropZone(new DropZoneController(
                Collections.singleton(Integer.valueOf(4)), item -> true));
        zone.handle(InventoryAction.place(4, new ItemStack(Material.STONE, 12), 12));

        MenuCloseResult result = menu.close(CloseReason.PLAYER);
        menu.close(CloseReason.PLUGIN_DISABLED);

        assertEquals(12, returned.get());
        assertEquals(CloseReason.PLAYER, result.reason());
        assertTrue(result.unreturned().isEmpty());
        assertTrue(zone.snapshot().isEmpty());
        assertTrue(menu.isClosed());
    }

    @Test
    void overflowIsExposedToTheCaller() {
        ScopeImpl parent = new ScopeImpl("test");
        MenuSession menu = MenuSession.open(parent, "overflow",
                MenuCompiler.compile(MenuTemplate.builder("Overflow", 1).build()),
                items -> items,
                items -> items);
        DropZoneController zone = menu.addDropZone(new DropZoneController(
                Collections.singleton(Integer.valueOf(0)), item -> true));
        zone.handle(InventoryAction.place(0, new ItemStack(Material.STONE, 4), 4));

        List<ItemStack> unreturned = menu.close(CloseReason.PLAYER).unreturned();

        assertEquals(1, unreturned.size());
        assertEquals(4, unreturned.get(0).getAmount());
    }

    @Test
    void returnFailureKeepsItemsVisibleForRecovery() {
        ScopeImpl parent = new ScopeImpl("test");
        MenuSession menu = MenuSession.open(parent, "failed-return",
                MenuCompiler.compile(MenuTemplate.builder("Failed", 1).build()),
                items -> {
                    throw new IllegalStateException("inventory unavailable");
                },
                items -> items);
        DropZoneController zone = menu.addDropZone(new DropZoneController(
                Collections.singleton(Integer.valueOf(0)), item -> true));
        zone.handle(InventoryAction.place(0, new ItemStack(Material.STONE, 3), 3));

        List<ItemStack> recoverable = menu.close(CloseReason.PLAYER).unreturned();
        assertEquals(1, recoverable.size());
        assertEquals(3, recoverable.get(0).getAmount());
    }

    @Test
    void dropZonesRequireShutdownFallback() {
        ScopeImpl parent = new ScopeImpl("test");
        MenuSession menu = MenuSession.open(parent, "unsafe",
                MenuCompiler.compile(MenuTemplate.builder("Unsafe", 1).build()),
                items -> items);

        assertThrows(IllegalStateException.class, () -> menu.addDropZone(
                new DropZoneController(Collections.singleton(Integer.valueOf(0)), item -> true)));
    }

    @Test
    void parentScopeShutdownDeliversDropZoneItemsToFallback() {
        ScopeImpl parent = new ScopeImpl("test");
        AtomicInteger delivered = new AtomicInteger();
        MenuSession menu = MenuSession.open(
                parent,
                "shutdown",
                MenuCompiler.compile(MenuTemplate.builder("Shutdown", 1).build()),
                items -> items,
                items -> {
                    for (ItemStack item : items) {
                        delivered.addAndGet(item.getAmount());
                    }
                    return Collections.emptyList();
                });
        DropZoneController zone = menu.addDropZone(new DropZoneController(
                Collections.singleton(Integer.valueOf(0)), item -> true));
        zone.handle(InventoryAction.place(0, new ItemStack(Material.STONE, 7), 7));

        parent.close();

        assertEquals(7, delivered.get());
        assertTrue(menu.close(CloseReason.SCOPE_CLOSED).unreturned().isEmpty());
    }
}
