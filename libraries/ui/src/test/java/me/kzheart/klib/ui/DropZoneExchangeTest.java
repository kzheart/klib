package me.kzheart.klib.ui;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import me.kzheart.klib.ui.drop.DropResult;
import me.kzheart.klib.ui.drop.DropZoneController;
import me.kzheart.klib.ui.drop.InventoryAction;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DropZoneExchangeTest {
    @Test
    void rejectedExchangePreservesBothStacksAndStillAllowsWithdrawal() throws Exception {
        try (MenuTestEnvironment f = new MenuTestEnvironment()) {
            AtomicBoolean allowed = new AtomicBoolean(true);
            DropZoneController zone = new DropZoneController(Collections.singleton(4), item -> allowed.get());
            f.menus.open(f.player, "exchange", model(), session -> session.addDropZone(zone));
            zone.handle(InventoryAction.place(4, new ItemStack(Material.STONE, 12), 12));
            allowed.set(false);
            InventoryView view = f.player.getOpenInventory();
            view.setCursor(new ItemStack(Material.DIRT, 7));

            click(f, view, org.bukkit.event.inventory.InventoryAction.SWAP_WITH_CURSOR);

            assertStack(zone.snapshot().get(4), Material.STONE, 12);
            assertStack(view.getCursor(), Material.DIRT, 7);
            view.setCursor(null);
            click(f, view, org.bukkit.event.inventory.InventoryAction.PICKUP_ALL);
            assertTrue(zone.snapshot().isEmpty());
            assertStack(view.getCursor(), Material.STONE, 12);
        }
    }

    @Test
    void acceptedExchangeMovesEachWholeStackExactlyOnce() throws Exception {
        try (MenuTestEnvironment f = new MenuTestEnvironment()) {
            DropZoneController zone = new DropZoneController(Collections.singleton(4), item -> true);
            f.menus.open(f.player, "exchange", model(), session -> session.addDropZone(zone));
            zone.handle(InventoryAction.place(4, new ItemStack(Material.STONE, 12), 12));
            InventoryView view = f.player.getOpenInventory();
            view.setCursor(new ItemStack(Material.DIRT, 7));

            click(f, view, org.bukkit.event.inventory.InventoryAction.SWAP_WITH_CURSOR);

            assertStack(zone.snapshot().get(4), Material.DIRT, 7);
            assertStack(view.getCursor(), Material.STONE, 12);
        }
    }

    @Test
    void oversizedOfferAndFailedAdmissionLeaveExistingContentsUntouched() {
        AtomicBoolean fail = new AtomicBoolean(false);
        DropZoneController zone = new DropZoneController(Collections.singleton(4), item -> {
            if (fail.get()) throw new IllegalStateException("progress unavailable");
            return true;
        });
        zone.handle(InventoryAction.place(4, new ItemStack(Material.STONE, 12), 12));
        assertEquals(0, zone.exchange(4, new ItemStack(Material.DIRT, 65)).accepted());
        assertStack(zone.snapshot().get(4), Material.STONE, 12);
        fail.set(true);
        assertThrows(IllegalStateException.class, () -> zone.exchange(4, new ItemStack(Material.DIRT, 7)));
        assertStack(zone.snapshot().get(4), Material.STONE, 12);
    }

    @Test
    void exchangeOwnsItsSnapshotAndDoesNotRetainPredicateOrCallerItems() {
        AtomicReference<ItemStack> predicateItem = new AtomicReference<ItemStack>();
        DropZoneController zone = new DropZoneController(Collections.singleton(4), item -> {
            predicateItem.set(item);
            return true;
        });
        zone.handle(InventoryAction.place(4, new ItemStack(Material.STONE, 12), 12));
        ItemStack offer = new ItemStack(Material.DIRT, 7);
        DropResult result = zone.exchange(4, offer);
        offer.setAmount(1);
        predicateItem.get().setAmount(2);
        result.removed().get().setAmount(3);
        assertStack(zone.snapshot().get(4), Material.DIRT, 7);
        assertStack(result.removed().get(), Material.STONE, 12);
    }

    private static MenuModel model() {
        return MenuCompiler.compile(MenuTemplate.builder("Exchange", 1).build());
    }

    private static void click(MenuTestEnvironment f, InventoryView view,
                              org.bukkit.event.inventory.InventoryAction action) {
        InventoryClickEvent event = new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER,
                4, ClickType.LEFT, action);
        f.menus.onClick(event);
        assertTrue(event.isCancelled());
    }

    private static void assertStack(ItemStack stack, Material material, int amount) {
        assertEquals(material, stack.getType());
        assertEquals(amount, stack.getAmount());
    }

}
