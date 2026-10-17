package me.kzheart.klib.ui;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import me.kzheart.klib.ui.drop.DropZoneController;
import me.kzheart.klib.ui.drop.InventoryAction;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MenuSlotDiffTest {
    @Test void equalVisualsSkipWritesButAlwaysPublishTheNewAction() throws Exception {
        try (MenuTestEnvironment f = new MenuTestEnvironment()) {
            AtomicInteger oldAction = new AtomicInteger();
            AtomicInteger newAction = new AtomicInteger();
            MenuHolder holder = f.menus.open(f.player, "diff", model(1, "same", oldAction));
            MenuTestEnvironment.Contents contents = f.contents(holder);
            assertEquals(Collections.singletonList(0), contents.writes);
            contents.writes.clear();
            holder.refresh(model(1, "same", newAction));
            assertTrue(contents.writes.isEmpty());
            holder.model().entry(0).get().action().accept(new MenuClick(f.player, 0));
            assertEquals(0, oldAction.get()); assertEquals(1, newAction.get());
            holder.refresh(model(2, "same", newAction));
            assertEquals(Collections.singletonList(0), contents.writes);
            contents.writes.clear();
            holder.refresh(model(2, "changed lore", newAction));
            assertEquals(Collections.singletonList(0), contents.writes);
            assertEquals(Collections.singletonList("changed lore"), contents.slots[0].getItemMeta().getLore());
        }
    }

    @Test void removalClearsOnlyPreviouslyOwnedDisplaySlotsAndPreservesInputOwnership() throws Exception {
        try (MenuTestEnvironment f = new MenuTestEnvironment()) {
            DropZoneController zone = new DropZoneController(Collections.singleton(1), item -> true);
            zone.handle(InventoryAction.place(1, new ItemStack(Material.DIAMOND, 3), 3));
            MenuHolder holder = f.menus.open(f.player, "input", model(1, "old", new AtomicInteger()), session -> session.addDropZone(zone));
            MenuTestEnvironment.Contents contents = f.contents(holder);
            contents.slots[5] = new ItemStack(Material.GOLD_INGOT, 4);
            contents.writes.clear();
            holder.refresh(MenuCompiler.compile(MenuTemplate.builder("Diff", 1).build()));
            assertEquals(Collections.singletonList(0), contents.writes);
            assertNull(contents.slots[0]);
            assertEquals(3, contents.slots[1].getAmount());
            assertEquals(4, contents.slots[5].getAmount());
            assertEquals(3, zone.snapshot().get(1).getAmount());
            assertThrows(IllegalArgumentException.class, () -> holder.refresh(MenuCompiler.compile(MenuTemplate.builder("Diff", 1)
                    .slot(1, MenuEntry.of(new ItemStack(Material.STONE))).build())));
            holder.close(CloseReason.PLAYER); holder.close(CloseReason.REPLACED);
            assertEquals(1, f.returned.size()); assertEquals(3, f.returned.get(0).getAmount());
        }
    }

    @Test void fullCandidateIsValidatedBeforeAnyCommitAndFailedCommitClosesTheSession() throws Exception {
        try (MenuTestEnvironment f = new MenuTestEnvironment()) {
            MenuHolder holder = f.menus.open(f.player, "diff", model(1, "old", new AtomicInteger()));
            MenuModel previous = holder.model();
            MenuTestEnvironment.Contents contents = f.contents(holder);
            contents.writes.clear();
            assertThrows(IllegalArgumentException.class, () -> holder.refresh(MenuCompiler.compile(MenuTemplate.builder("Other", 1).build())));
            assertThrows(IllegalArgumentException.class, () -> holder.refresh(MenuCompiler.compile(MenuTemplate.builder("Diff", 2).build())));
            assertTrue(contents.writes.isEmpty()); assertSame(previous, holder.model());
            contents.failSlot = 2;
            MenuModel candidate = MenuCompiler.compile(MenuTemplate.builder("Diff", 1)
                    .slot(0, MenuEntry.of(new ItemStack(Material.GOLD_INGOT)))
                    .slot(2, MenuEntry.of(new ItemStack(Material.DIAMOND))).build());
            assertThrows(IllegalStateException.class, () -> holder.refresh(candidate));
            assertTrue(holder.session().isClosed());
            assertEquals(CloseReason.RENDER_FAILED, holder.close(CloseReason.PLAYER).reason());
            assertSame(previous, holder.model(), "failed partial visual writes never publish new handlers");
            assertNotSame(holder.getInventory(), f.player.getOpenInventory().getTopInventory());
            assertThrows(IllegalStateException.class, holder::refresh);
        }
    }

    @Test void copiesProtectTheSnapshotFromExternalMutation() throws Exception {
        try (MenuTestEnvironment f = new MenuTestEnvironment()) {
            ItemStack source = MenuTestEnvironment.icon(Material.STONE, 2, "source");
            MenuEntry entry = MenuEntry.of(source);
            MenuHolder holder = f.menus.open(f.player, "copy", MenuCompiler.compile(MenuTemplate.builder("Diff", 1).slot(0, entry).build()));
            source.setAmount(40); entry.item().setAmount(50);
            assertEquals(2, f.contents(holder).slots[0].getAmount());
            f.contents(holder).writes.clear(); holder.refresh();
            assertTrue(f.contents(holder).writes.isEmpty());
        }
    }

    private static MenuModel model(int amount, String lore, AtomicInteger action) {
        return MenuCompiler.compile(MenuTemplate.builder("Diff", 1)
                .slot(0, MenuEntry.of(MenuTestEnvironment.icon(Material.STONE, amount, lore), click -> action.incrementAndGet())).build());
    }
}
