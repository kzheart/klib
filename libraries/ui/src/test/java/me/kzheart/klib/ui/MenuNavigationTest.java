package me.kzheart.klib.ui;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import me.kzheart.klib.scope.Scope;
import me.kzheart.klib.ui.drop.DropZoneController;
import me.kzheart.klib.ui.drop.InventoryAction;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MenuNavigationTest {
    @Test void pushAndBackWaitATickKeepParentStateAndUseTheActualParent() throws Exception {
        try (MenuTestEnvironment f = new MenuTestEnvironment()) {
            AtomicReference<ReactiveMenuSession<Integer>> parent = new AtomicReference<>();
            AtomicReference<ReactiveMenuSession<String>> child = new AtomicReference<>();
            AtomicInteger released = new AtomicInteger();
            AtomicInteger rootBack = new AtomicInteger();
            MenuPage<Integer> browse = MenuPage.<Integer>builder("browse", ui -> model("Browse", 1))
                .onOpen(ui -> { parent.set(ui); ui.bind(released::incrementAndGet); }).build();
            MenuPage<String> details = MenuPage.<String>builder("details", ui -> model("Details", 1))
                .onOpen(child::set).build();
            f.menus.openReactive(f.player, browse, 2, rootBack::incrementAndGet);
            ReactiveMenuSession<Integer> first = parent.get();
            first.update(value -> 7); f.tick();
            Scope oldScope = first.scope();
            assertTrue(first.push(details, "chosen"));
            assertFalse(first.push(details, "duplicate"));
            assertFalse(first.back());
            assertEquals(1, f.opens); assertNull(child.get());
            f.tick();
            assertEquals(2, f.opens); assertTrue(oldScope.isClosed()); assertEquals(1, released.get());
            assertEquals("chosen", child.get().state());
            assertTrue(child.get().back()); assertEquals(2, f.opens);
            f.tick();
            assertNotSame(first, parent.get());
            assertEquals(Integer.valueOf(7), parent.get().state());
            assertTrue(parent.get().back()); assertFalse(parent.get().back());
            assertEquals(0, rootBack.get()); f.tick(); assertEquals(1, rootBack.get());

            // Same child definition entered from a different root must return to that root.
            f.menus.openReactive(f.player, browse, 99, rootBack::incrementAndGet);
            parent.get().push(details, "other"); f.tick(); child.get().back(); f.tick();
            assertEquals(Integer.valueOf(99), parent.get().state());
        }
    }

    @Test void changesBeforeNavigationAndExternalReplacementInvalidateQueuedRequests() throws Exception {
        try (MenuTestEnvironment f = new MenuTestEnvironment()) {
            AtomicInteger mounted = new AtomicInteger();
            MenuPage<Integer> child = MenuPage.<Integer>builder("child", ui -> model("Child", 1))
                .onOpen(ui -> mounted.incrementAndGet()).build();
            ReactiveMenuSession<Integer> ui = f.menus.openReactive(f.player, "root", 0, s -> model("Root", 1));
            ui.push(child, 1); ui.update(s -> 2); f.tick();
            assertEquals(0, mounted.get()); assertTrue(ui.isCurrent());
            ui.push(child, 3);
            f.player.openInventory(f.inventory(null, 9));
            f.tick(); assertEquals(0, mounted.get());
            assertThrows(IllegalStateException.class, ui::back);
            assertEquals(0, f.queued());
        }
    }

    @Test void structuralReopenKeepsStateAndNavigationButReleasesWindowResourcesAndReads() throws Exception {
        try (MenuTestEnvironment f = new MenuTestEnvironment()) {
            AtomicReference<ReactiveMenuSession<Integer>> root = new AtomicReference<>();
            AtomicReference<ReactiveMenuSession<Integer>> active = new AtomicReference<>();
            AtomicReference<DropZoneController> input = new AtomicReference<>();
            AtomicInteger mounted = new AtomicInteger(), released = new AtomicInteger();
            MenuPage<Integer> parent = MenuPage.<Integer>builder("root", ui -> model("Root", 1)).onOpen(root::set).build();
            MenuPage<Integer> dynamic = MenuPage.<Integer>builder("dynamic", ui -> model("Title-" + ui.state(), ui.state() + 1))
                .configureWindow(session -> {
                    DropZoneController zone = new DropZoneController(Collections.singleton(1), item -> true);
                    session.addDropZone(zone); input.set(zone);
                })
                .onOpen(ui -> { active.set(ui); mounted.incrementAndGet(); ui.bind(released::incrementAndGet); }).build();
            f.menus.openReactive(f.player, parent, 13, () -> { });
            root.get().push(dynamic, 0); f.tick();
            ReactiveMenuSession<Integer> ui = active.get();
            MenuHolder old = ui.holder(); Scope oldScope = ui.scope();
            DropZoneController oldInput = input.get();
            oldInput.handle(InventoryAction.place(1, new ItemStack(Material.DIAMOND, 3), 3));
            ui.update(state -> 1);
            CompletableFuture<Integer> source = new CompletableFuture<>();
            CompletionStage<Boolean> observation = ui.readLatest("count", source, (s, n) -> n, failure -> fail(failure));
            f.tick();
            assertSame(old, ui.holder(), "structural reopen uses the navigation tick after the refresh tick");
            assertFalse(observation.toCompletableFuture().join()); assertFalse(source.isDone());
            assertEquals(0, released.get());
            f.tick();
            assertSame(ui, active.get()); assertNotSame(old, ui.holder());
            assertEquals(Integer.valueOf(1), ui.state()); assertEquals("Title-1", ui.holder().model().title());
            assertEquals(18, ui.holder().model().size());
            assertTrue(oldScope.isClosed()); assertTrue(oldInput.isClosed());
            assertEquals(2, mounted.get()); assertEquals(1, released.get());
            assertEquals(1, f.returned.size()); assertEquals(3, f.returned.get(0).getAmount());
            assertTrue(input.get().snapshot().isEmpty());
            CompletionStage<Boolean> fresh = ui.readLatest("count", CompletableFuture.completedFuture(1), (s, n) -> n, failure -> fail(failure));
            source.complete(100); f.tick(); assertTrue(fresh.toCompletableFuture().join());
            assertEquals(Integer.valueOf(1), ui.state());
            old.close(CloseReason.PLAYER); assertTrue(ui.isCurrent());
            assertEquals(1, f.returned.size());
            ui.back(); f.tick();
            assertEquals(Integer.valueOf(13), root.get().state()); assertEquals(2, released.get());
            assertEquals(1, f.returned.size());
        }
    }

    @Test void staleStructuralReopenCannotOverwriteNewerStateOrAReconnectedPlayer() throws Exception {
        try (MenuTestEnvironment f = new MenuTestEnvironment()) {
            ReactiveMenuSession<Integer> ui = f.menus.openReactive(f.player, "dynamic", 0, s -> model("T" + s.state(), 1));
            MenuHolder first = ui.holder();
            ui.update(s -> 1); f.tick();
            ui.update(s -> 2); f.tick();
            assertSame(first, ui.holder());
            f.tick(); assertEquals("T2", ui.holder().model().title());
            int opens = f.opens;
            ui.update(s -> 3); f.tick();
            f.player(f.player.getUniqueId());
            f.tick(); assertEquals(opens, f.opens); assertFalse(ui.isCurrent());
        }
    }

    @Test void aRevertedConfigurationUsesDiffInsteadOfUnnecessarilyReturningInput() throws Exception {
        try (MenuTestEnvironment f = new MenuTestEnvironment()) {
            AtomicReference<String> title = new AtomicReference<>("Original");
            ReactiveMenuSession<Integer> ui = f.menus.openReactive(f.player, "reload", 0, s -> model(title.get(), 1));
            MenuHolder original = ui.holder();
            title.set("Changed"); ui.invalidate(); f.tick();
            title.set("Original"); f.tick();
            assertSame(original, ui.holder()); assertEquals(1, f.opens);
        }
    }

    @Test void sizeAloneReopensAndAnExternalWindowWinsOverAPendingReopen() throws Exception {
        try (MenuTestEnvironment f = new MenuTestEnvironment()) {
            ReactiveMenuSession<Integer> ui = f.menus.openReactive(f.player, "resize", 1, s -> model("Same title", s.state()));
            MenuHolder original = ui.holder();
            ui.update(s -> 2); f.tick(); assertSame(original, ui.holder());
            f.tick(); assertNotSame(original, ui.holder()); assertEquals(18, ui.holder().model().size());
            ui.update(s -> 3); f.tick();
            f.player.openInventory(f.inventory(null, 9)); int opens = f.opens;
            f.tick(); assertEquals(opens, f.opens); assertFalse(ui.isCurrent());
        }
    }

    @Test void manualCloseQuitAndParentScopeCloseNeverRestoreAParent() throws Exception {
        for (int mode = 0; mode < 3; mode++) {
            try (MenuTestEnvironment f = new MenuTestEnvironment()) {
                AtomicReference<ReactiveMenuSession<Integer>> active = new AtomicReference<>();
                MenuPage<Integer> page = MenuPage.<Integer>builder("page", ui -> model("Page", 1)).onOpen(active::set).build();
                f.menus.openReactive(f.player, page, 0, () -> fail("closed root callback"));
                active.get().push(page, 1); f.tick();
                ReactiveMenuSession<Integer> child = active.get();
                child.back();
                if (mode == 0) f.player.closeInventory();
                else if (mode == 1) f.menus.onQuit(new PlayerQuitEvent(f.player, "quit"));
                else f.scope.close();
                int opens = f.opens; f.tick();
                assertEquals(opens, f.opens); assertFalse(child.isCurrent());
                assertThrows(IllegalStateException.class, child::back);
            }
        }
    }

    @Test void failedReopenReturnsInputAndClosesTheChainWithoutPublishingAPartialWindow() throws Exception {
        try (MenuTestEnvironment f = new MenuTestEnvironment()) {
            AtomicReference<DropZoneController> input = new AtomicReference<>();
            MenuPage<Integer> page = MenuPage.<Integer>builder("dynamic", ui ->
                MenuCompiler.compile(MenuTemplate.builder("T" + ui.state(), 1)
                    .slot(0, MenuEntry.of(new ItemStack(Material.STONE))).build()))
                .configureWindow(session -> input.set(session.addDropZone(new DropZoneController(Collections.singleton(1), item -> true))))
                .build();
            ReactiveMenuSession<Integer> ui = f.menus.openReactive(f.player, page, 0, () -> { });
            MenuHolder old = ui.holder();
            input.get().handle(InventoryAction.place(1, new ItemStack(Material.GOLD_INGOT, 2), 2));
            ui.update(s -> 1); f.tick(); f.failNextInventorySlot = 0;
            assertThrows(IllegalStateException.class, f::tick);
            assertFalse(ui.isCurrent()); assertTrue(old.session().isClosed());
            assertEquals(1, f.returned.size()); assertEquals(2, f.returned.get(0).getAmount());
            assertThrows(IllegalStateException.class, ui::back);
            assertFalse(f.player.getOpenInventory().getTopInventory().getHolder() instanceof MenuHolder);
        }
    }

    @Test void openingAndViewFailuresReleaseSubscriptionsAndCloseTheOwnedWindow() throws Exception {
        try (MenuTestEnvironment f = new MenuTestEnvironment()) {
            AtomicInteger released = new AtomicInteger();
            MenuPage<Integer> badMount = MenuPage.<Integer>builder("bad", ui -> model("Bad", 1))
                .onOpen(ui -> { ui.bind(released::incrementAndGet); throw new IllegalStateException("mount failed"); }).build();
            assertThrows(IllegalStateException.class, () -> f.menus.openReactive(f.player, badMount, 0, () -> { }));
            assertEquals(1, released.get());
            assertFalse(f.player.getOpenInventory().getTopInventory().getHolder() instanceof MenuHolder);
            ReactiveMenuSession<Integer> ui = f.menus.openReactive(f.player, "view", 0, s -> {
                if (s.state() == 1) throw new IllegalArgumentException("view failed");
                return model("View", 1);
            });
            ui.update(s -> 1); assertThrows(IllegalArgumentException.class, f::tick);
            assertFalse(ui.isCurrent());
        }
    }

    @Test void unsubscribeFailureStillClosesTheOldPhysicalWindowAndReturnsItemsOnce() throws Exception {
        try (MenuTestEnvironment f = new MenuTestEnvironment()) {
            AtomicReference<DropZoneController> input = new AtomicReference<>();
            MenuPage<Integer> parent = MenuPage.<Integer>builder("parent", ui -> model("Parent", 1))
                .configureWindow(session -> input.set(session.addDropZone(new DropZoneController(Collections.singleton(1), item -> true))))
                .onOpen(ui -> ui.bind(() -> { throw new IllegalStateException("unsubscribe failed"); })).build();
            MenuPage<Integer> child = MenuPage.<Integer>builder("child", ui -> model("Child", 1)).build();
            ReactiveMenuSession<Integer> ui = f.menus.openReactive(f.player, parent, 0, () -> { });
            input.get().handle(InventoryAction.place(1, new ItemStack(Material.DIAMOND, 2), 2));
            ui.push(child, 0);
            assertThrows(RuntimeException.class, f::tick);
            assertFalse(ui.isCurrent()); assertThrows(IllegalStateException.class, ui::back);
            assertFalse(f.player.getOpenInventory().getTopInventory().getHolder() instanceof MenuHolder);
            assertEquals(1, f.returned.size()); assertEquals(2, f.returned.get(0).getAmount());
        }
    }

    @Test void aClosedHolderProtectsClickAndDragUntilItsActualWindowCloses() throws Exception {
        try (MenuTestEnvironment f = new MenuTestEnvironment()) {
            AtomicInteger actions = new AtomicInteger();
            MenuHolder holder = f.menus.open(f.player, "protected", MenuCompiler.compile(MenuTemplate.builder("Protected", 1)
                .cancelClicks(false).slot(0, MenuEntry.of(new ItemStack(Material.DIAMOND), click -> actions.incrementAndGet())).build()));
            InventoryView oldView = f.player.getOpenInventory();
            holder.close(CloseReason.REPLACED);
            assertSame(holder, oldView.getTopInventory().getHolder());
            for (ClickType type : new ClickType[] { ClickType.LEFT, ClickType.SHIFT_LEFT, ClickType.NUMBER_KEY, ClickType.UNKNOWN, ClickType.DOUBLE_CLICK, ClickType.DROP }) {
                for (int slot : new int[] { 0, 10 }) {
                    InventoryClickEvent event = new InventoryClickEvent(oldView, InventoryType.SlotType.CONTAINER, slot, type,
                        org.bukkit.event.inventory.InventoryAction.NOTHING);
                    f.menus.onClick(event); assertTrue(event.isCancelled());
                }
            }
            Map<Integer, ItemStack> slots = new HashMap<>(); slots.put(10, new ItemStack(Material.STONE));
            InventoryDragEvent drag = new InventoryDragEvent(oldView, null, new ItemStack(Material.STONE), false, slots);
            f.menus.onDrag(drag); assertTrue(drag.isCancelled());
            assertEquals(0, actions.get());
        }
    }

    private static MenuModel model(String title, int rows) {
        return MenuCompiler.compile(MenuTemplate.builder(title, rows).build());
    }
}
