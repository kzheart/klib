package me.kzheart.klib.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Independent browsing consumer adapted from MulanShop ShopMenu's category/counter refresh flow.
 * No viewers/request-token/attached/nextTick bookkeeping or payment logic is copied into it.
 */
class ShopBrowseConsumerTest {
    @Test void browsingUsesLatestCountersAndUnsubscribesWithoutCancellingDomainWork() throws Exception {
        try (MenuTestEnvironment f = new MenuTestEnvironment()) {
            Changes changes = new Changes();
            AtomicInteger queries = new AtomicInteger();
            CompletableFuture<Integer> slow = new CompletableFuture<Integer>();
            CompletableFuture<Integer> fast = new CompletableFuture<Integer>();
            ShopBrowse browser = new ShopBrowse(f.menus, changes, () -> queries.getAndIncrement() == 0 ? slow : fast);
            ReactiveMenuSession<Browse> ui = browser.open(f.player);
            CompletionStage<Boolean> older = browser.refresh(ui);
            CompletionStage<Boolean> newer = browser.refresh(ui);
            assertFalse(older.toCompletableFuture().join());
            f.worker(() -> fast.complete(4)); f.tick(); f.tick();
            assertTrue(newer.toCompletableFuture().join());
            assertEquals(4, ui.state().available);
            slow.complete(1); f.tick();
            assertEquals(4, ui.state().available);
            int initialOpens = f.opens;
            ui.holder().model().entry(7).get().action().accept(new MenuClick(f.player, 7));
            f.tick();
            assertEquals("tools", ui.state().category);
            assertEquals(initialOpens, f.opens, "category changes reuse the inventory");
            assertEquals(2, queries.get(), "pure rendering does not query the domain");
            MenuTestEnvironment.Contents contents = f.contents(ui.holder());
            contents.writes.clear();
            f.worker(() -> { changes.fire(); changes.fire(); changes.fire(); });
            f.tick(); assertTrue(contents.writes.isEmpty(), "unchanged snapshot produces zero writes");
            assertEquals(1, changes.listeners.size());
            ui.holder().close(CloseReason.PLAYER);
            assertTrue(changes.listeners.isEmpty());
        }
    }

    @Test void aResponseForThePreviousCategoryCannotOverwriteTheCurrentSelection() throws Exception {
        try (MenuTestEnvironment f = new MenuTestEnvironment()) {
            CompletableFuture<Integer> source = new CompletableFuture<Integer>();
            ShopBrowse browser = new ShopBrowse(f.menus, new Changes(), () -> source);
            ReactiveMenuSession<Browse> ui = browser.open(f.player);
            CompletionStage<Boolean> response = browser.refresh(ui);
            ui.update(state -> new Browse("tools", 0, 9));
            source.complete(2); f.tick();
            assertFalse(response.toCompletableFuture().join());
            assertEquals("tools", ui.state().category); assertEquals(9, ui.state().available);
        }
    }

    private static final class ShopBrowse {
        final MenuRenderer menus;
        final Changes changes;
        final Supplier<CompletableFuture<Integer>> counters;
        ShopBrowse(MenuRenderer menus, Changes changes, Supplier<CompletableFuture<Integer>> counters) {
            this.menus = menus; this.changes = changes; this.counters = counters;
        }
        ReactiveMenuSession<Browse> open(Player player) {
            ReactiveMenuSession<Browse> ui = menus.openReactive(player, "shop-browse", new Browse("food", 0, 0), this::render);
            Runnable changed = ui::invalidate;
            changes.listeners.add(changed);
            ui.bind(() -> changes.listeners.remove(changed));
            return ui;
        }
        CompletionStage<Boolean> refresh(ReactiveMenuSession<Browse> ui) {
            return ui.readLatest("counters", counters.get(),
                    (state, available) -> new Browse(state.category, state.page, available), failure -> fail(failure));
        }
        MenuModel render(ReactiveMenuSession<Browse> ui) {
            Browse state = ui.state();
            Page<String> page = new Paginator<String>(Collections.singletonList(state.category + ":entry"), 1).page(state.page);
            return MenuCompiler.compile(MenuTemplate.builder("Shop", 1)
                    .slot(0, MenuEntry.of(MenuTestEnvironment.icon(Material.PAPER, 1, page.values().get(0) + " available=" + state.available)))
                    .slot(7, MenuEntry.of(MenuTestEnvironment.icon(Material.ARROW, 1, "tools"),
                            click -> ui.update(old -> new Browse("tools", 0, old.available))))
                    .slot(8, MenuEntry.of(MenuTestEnvironment.icon(Material.BARRIER, 1, "close"),
                            click -> ui.navigate(() -> ui.viewer().closeInventory())))
                    .build());
        }
    }
    private static final class Browse {
        final String category; final int page; final int available;
        Browse(String category, int page, int available) { this.category = category; this.page = page; this.available = available; }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Browse)) return false;
            Browse value = (Browse) other;
            return category.equals(value.category) && page == value.page && available == value.available;
        }
        @Override public int hashCode() { return Objects.hash(category, page, available); }
    }
    private static final class Changes {
        final List<Runnable> listeners = new ArrayList<Runnable>();
        void fire() { for (Runnable listener : new ArrayList<Runnable>(listeners)) listener.run(); }
    }
}
