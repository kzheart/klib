package me.kzheart.klib.ui;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import me.kzheart.klib.scope.Scope;
import me.kzheart.klib.ui.drop.DropZoneController;
import me.kzheart.klib.ui.drop.InventoryAction;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class QueuedNavigationRefreshTest {
    @Test void staleNavigationCannotLoseAnEarlierQueuedStructuralRefresh() throws Exception {
        for (Shape shape : Shape.structural()) {
            for (Navigation navigation : Navigation.values()) {
                try (Scenario s = new Scenario(shape, navigation)) {
                    MenuHolder original = s.ui.holder();
                    int opens = s.f.opens;
                    s.queueStaleNavigation();
                    settle(s.f, s.label());

                    assertTrue(s.ui.isCurrent(), s.label());
                    assertEquals(Integer.valueOf(2), s.ui.state(), s.label());
                    assertLatest(s, 2);
                    assertNotSame(original, s.ui.holder(), s.label());
                    assertTrue(original.session().isClosed(), s.label());
                    assertEquals(opens + 1, s.f.opens, s.label());
                    assertEquals(2, s.mounts.get(), s.label());
                    assertEquals(1, s.releases.get(), s.label());
                    s.assertNavigationDidNotRun();
                }
            }
        }
    }

    @Test void sameRevisionNavigationStillRunsOnceWhileARefreshIsWaiting() throws Exception {
        for (Shape shape : Shape.structural()) {
            for (Navigation navigation : Navigation.values()) {
                try (Scenario s = new Scenario(shape, navigation)) {
                    MenuHolder original = s.ui.holder();
                    int opens = s.f.opens;
                    s.ui.update(state -> 2);
                    assertTrue(s.requestNavigation(), s.label());
                    settle(s.f, s.label());

                    assertEquals(opens + 1, s.f.opens, s.label());
                    assertEquals(1, s.releases.get(), s.label());
                    assertTrue(original.session().isClosed(), s.label());
                    if (navigation == Navigation.BACK || navigation == Navigation.PUSH) {
                        ReactiveMenuSession<Integer> destination = navigation == Navigation.BACK
                            ? s.parent.get() : s.destination.get();
                        assertNotNull(destination, s.label());
                        assertTrue(destination.isCurrent(), s.label());
                        assertFalse(s.ui.isCurrent(), s.label());
                        assertEquals(navigation == Navigation.BACK ? 2 : 0, s.parentMounts.get(), s.label());
                        assertEquals(navigation == Navigation.PUSH ? 1 : 0, s.destinationMounts.get(), s.label());
                        assertEquals(1, s.mounts.get(), "a departed page must not remount: " + s.label());
                    } else {
                        assertTrue(s.ui.isCurrent(), s.label());
                        assertEquals(2, s.mounts.get(), s.label());
                        assertLatest(s, 2);
                    }
                    assertEquals(navigation == Navigation.ROOT_BACK ? 1 : 0, s.rootBacks.get(), s.label());
                    assertEquals(navigation == Navigation.NOOP ? 1 : 0, s.callbacks.get(), s.label());
                    click(s.f, s.f.player, s.clicked, 2, s.label());
                }
            }
        }
    }

    @Test void nonStructuralRefreshKeepsItsHolderAndReplacesActionsWithoutItemWrites() throws Exception {
        for (Navigation navigation : Navigation.values()) {
            try (Scenario s = new Scenario(Shape.UNCHANGED, navigation)) {
                MenuHolder original = s.ui.holder();
                int opens = s.f.opens;
                s.f.contents(original).writes.clear();
                s.queueStaleNavigation();
                settle(s.f, s.label());

                assertSame(original, s.ui.holder(), s.label());
                assertEquals(opens, s.f.opens, s.label());
                assertEquals(1, s.mounts.get(), s.label());
                assertEquals(0, s.releases.get(), s.label());
                assertTrue(s.f.contents(original).writes.isEmpty(), s.label());
                assertLatest(s, 2);
                s.assertNavigationDidNotRun();
            }
        }
    }

    @Test void recoveredStructuralRefreshReturnsInputAndReleasesEachWindowOnlyOnce() throws Exception {
        try (Scenario s = new Scenario(Shape.TITLE_AND_SIZE, Navigation.BACK)) {
            MenuHolder original = s.ui.holder();
            Scope originalScope = s.ui.scope();
            DropZoneController originalInput = s.input.get();
            originalInput.handle(InventoryAction.place(1, new ItemStack(Material.DIAMOND, 3), 3));
            s.queueStaleNavigation();
            settle(s.f, s.label());

            assertLatest(s, 2);
            assertTrue(originalScope.isClosed());
            assertTrue(originalInput.isClosed());
            assertEquals(1, s.releases.get());
            assertEquals(1, s.f.returned.size());
            assertEquals(Material.DIAMOND, s.f.returned.get(0).getType());
            assertEquals(3, s.f.returned.get(0).getAmount());
            assertTrue(s.input.get().snapshot().isEmpty());

            original.close(CloseReason.PLAYER);
            originalScope.close();
            settle(s.f, s.label());
            assertTrue(s.ui.isCurrent());
            assertEquals(1, s.releases.get());
            assertEquals(1, s.f.returned.size());

            DropZoneController replacementInput = s.input.get();
            Scope replacementScope = s.ui.scope();
            assertTrue(s.ui.back(), "the recovered page retains its parent navigation");
            settle(s.f, s.label());
            assertTrue(s.parent.get().isCurrent());
            assertEquals(Integer.valueOf(2), s.parent.get().state());
            assertTrue(replacementScope.isClosed());
            assertTrue(replacementInput.isClosed());
            assertEquals(2, s.releases.get());
            assertEquals(1, s.f.returned.size());
            assertEquals(0, s.rootBacks.get());
        }
    }

    @Test void pendingRefreshNeverRevivesAfterCloseQuitReplacementReconnectOrScopeClose() throws Exception {
        for (Termination termination : Termination.values()) {
            for (boolean afterRefreshTick : new boolean[] { false, true }) {
                try (Scenario s = new Scenario(Shape.TITLE_AND_SIZE, Navigation.ROOT_BACK)) {
                    String label = termination + "/afterRefreshTick=" + afterRefreshTick;
                    s.queueStaleNavigation();
                    if (afterRefreshTick) s.f.tick();

                    Player survivor = s.f.player;
                    switch (termination) {
                        case CLOSE:
                            s.f.player.closeInventory();
                            break;
                        case QUIT:
                            s.f.menus.onQuit(new PlayerQuitEvent(s.f.player, "quit"));
                            break;
                        case REPLACEMENT:
                            s.f.player.openInventory(s.f.inventory(null, 9));
                            break;
                        case RECONNECT:
                            survivor = s.f.player(s.f.player.getUniqueId());
                            s.f.menus.open(survivor, "replacement", model("Replacement", 1, 2, s.clicked));
                            break;
                        case SCOPE_CLOSE:
                            s.f.scope.close();
                            break;
                        default:
                            throw new AssertionError(termination);
                    }
                    Inventory survivingInventory = survivor.getOpenInventory().getTopInventory();
                    int opens = s.f.opens;
                    settle(s.f, label);

                    assertEquals(opens, s.f.opens, label);
                    assertSame(survivingInventory, survivor.getOpenInventory().getTopInventory(), label);
                    assertFalse(s.ui.isCurrent(), label);
                    assertThrows(IllegalStateException.class, s.ui::back, label);
                    assertEquals(1, s.mounts.get(), label);
                    assertEquals(0, s.rootBacks.get(), label);
                    if (termination != Termination.RECONNECT) assertEquals(1, s.releases.get(), label);
                    else click(s.f, survivor, s.clicked, 2, label);
                }
            }
        }
    }

    /** No invalidate is allowed here: queued work must make progress by itself. */
    private static void settle(MenuTestEnvironment f, String label) {
        for (int tick = 0; tick < 6 && f.queued() > 0; tick++) f.tick();
        assertEquals(0, f.queued(), "queue did not settle: " + label);
        int opens = f.opens;
        f.tick();
        f.tick();
        assertEquals(0, f.queued(), "settled work was requeued: " + label);
        assertEquals(opens, f.opens, "settled window reopened again: " + label);
    }

    private static void assertLatest(Scenario s, int state) {
        assertAll(s.label(),
            () -> assertEquals(s.shape.title(state), s.ui.holder().model().title(), s.label()),
            () -> assertEquals(s.shape.rows(state) * 9, s.ui.holder().model().size(), s.label()),
            () -> assertSame(s.ui.holder(), s.f.player.getOpenInventory().getTopInventory().getHolder(), s.label()),
            () -> click(s.f, s.f.player, s.clicked, state, s.label()));
    }

    private static void click(MenuTestEnvironment f, Player player, AtomicInteger clicked, int expected, String label) {
        clicked.set(-1);
        InventoryClickEvent event = new InventoryClickEvent(player.getOpenInventory(),
            InventoryType.SlotType.CONTAINER, 0, ClickType.LEFT, org.bukkit.event.inventory.InventoryAction.NOTHING);
        f.menus.onClick(event);
        assertTrue(event.isCancelled(), label);
        assertEquals(expected, clicked.get(), "click must use the latest rendered action: " + label);
    }

    private static MenuModel model(String title, int rows, int capturedState, AtomicInteger clicked) {
        // Identical icons deliberately cannot reveal a stale action through inventory diffs.
        return MenuCompiler.compile(MenuTemplate.builder(title, rows)
            .slot(0, MenuEntry.of(new ItemStack(Material.STONE), click -> clicked.set(capturedState))).build());
    }

    private enum Shape {
        TITLE, SIZE, TITLE_AND_SIZE, UNCHANGED;

        static Shape[] structural() { return new Shape[] { TITLE, SIZE, TITLE_AND_SIZE }; }
        String title(int state) { return this == TITLE || this == TITLE_AND_SIZE ? "T" + state : "Same title"; }
        int rows(int state) { return this == SIZE || this == TITLE_AND_SIZE ? state + 1 : 1; }
    }

    private enum Navigation { BACK, ROOT_BACK, PUSH, NOOP }
    private enum Termination { CLOSE, QUIT, REPLACEMENT, RECONNECT, SCOPE_CLOSE }

    private static final class Scenario implements AutoCloseable {
        final MenuTestEnvironment f = new MenuTestEnvironment();
        final Shape shape;
        final Navigation navigation;
        final AtomicInteger clicked = new AtomicInteger(-1);
        final AtomicInteger mounts = new AtomicInteger();
        final AtomicInteger releases = new AtomicInteger();
        final AtomicInteger parentMounts = new AtomicInteger();
        final AtomicInteger destinationMounts = new AtomicInteger();
        final AtomicInteger rootBacks = new AtomicInteger();
        final AtomicInteger callbacks = new AtomicInteger();
        final AtomicReference<ReactiveMenuSession<Integer>> active = new AtomicReference<>();
        final AtomicReference<ReactiveMenuSession<Integer>> parent = new AtomicReference<>();
        final AtomicReference<ReactiveMenuSession<Integer>> destination = new AtomicReference<>();
        final AtomicReference<DropZoneController> input = new AtomicReference<>();
        final MenuPage<Integer> destinationPage;
        final ReactiveMenuSession<Integer> ui;

        Scenario(Shape shape, Navigation navigation) throws Exception {
            this.shape = shape;
            this.navigation = navigation;
            destinationPage = MenuPage.<Integer>builder("destination", session -> model("Destination", 1, session.state(), clicked))
                .onOpen(session -> { destination.set(session); destinationMounts.incrementAndGet(); }).build();
            MenuPage<Integer> dynamic = MenuPage.<Integer>builder("dynamic", session -> {
                int state = session.state();
                return model(shape.title(state), shape.rows(state), state, clicked);
            }).configureWindow(session -> input.set(session.addDropZone(
                new DropZoneController(Collections.singleton(1), item -> true))))
                .onOpen(session -> {
                    active.set(session);
                    mounts.incrementAndGet();
                    session.bind(releases::incrementAndGet);
                }).build();
            if (navigation == Navigation.BACK) {
                MenuPage<Integer> parentPage = MenuPage.<Integer>builder("parent", session -> model("Parent", 1, session.state(), clicked))
                    .onOpen(session -> { parent.set(session); parentMounts.incrementAndGet(); }).build();
                f.menus.openReactive(f.player, parentPage, 2, rootBacks::incrementAndGet);
                assertTrue(parent.get().push(dynamic, 0));
                f.tick();
            } else {
                f.menus.openReactive(f.player, dynamic, 0, rootBacks::incrementAndGet);
            }
            ui = active.get();
            assertNotNull(ui);
            assertEquals(0, f.queued());
        }

        String label() { return shape + "/" + navigation; }

        boolean requestNavigation() {
            switch (navigation) {
                case BACK:
                case ROOT_BACK:
                    return ui.back();
                case PUSH:
                    return ui.push(destinationPage, 2);
                case NOOP:
                    return ui.navigate(callbacks::incrementAndGet);
                default:
                    throw new AssertionError(navigation);
            }
        }

        void queueStaleNavigation() {
            ui.update(state -> 1);
            assertTrue(requestNavigation(), label());
            ui.update(state -> 2);
            assertEquals(2, f.queued(), "one coalesced refresh and one navigation: " + label());
        }

        void assertNavigationDidNotRun() {
            assertEquals(navigation == Navigation.BACK ? 1 : 0, parentMounts.get(), label());
            assertEquals(0, destinationMounts.get(), label());
            assertEquals(0, rootBacks.get(), label());
            assertEquals(0, callbacks.get(), label());
        }

        @Override public void close() throws IllegalAccessException { f.close(); }
    }
}
