package me.kzheart.klib.ui;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Independent Codex-style consumer: scan once, keep immutable lore pages by stable entry id. */
class CodexLoreConsumerTest {
    @Test void lorePagingChangesOneSlotWithoutRescanningOrReopening() throws Exception {
        try (MenuTestEnvironment f = new MenuTestEnvironment()) {
            CodexBrowse codex = new CodexBrowse(f.menus);
            ReactiveMenuSession<CodexState> ui = codex.open(f.player);
            MenuHolder holder = ui.holder();
            MenuTestEnvironment.Contents contents = f.contents(holder);
            contents.writes.clear();
            holder.model().entry(0).get().action().accept(new MenuClick(f.player, 0));
            assertEquals(1, codex.scans.get()); assertEquals(1, f.opens);
            assertTrue(contents.writes.isEmpty(), "the click stack does not perform a render");
            f.tick();
            assertEquals(Collections.singletonList(0), contents.writes);
            assertEquals(1, codex.scans.get()); assertEquals(1, f.opens);
            assertEquals(Collections.singletonList("apple lore 1"), contents.slots[0].getItemMeta().getLore());
            assertNotNull(contents.slots[8], "static close/back decoration survives partial updates");
            Player second = f.player(java.util.UUID.randomUUID());
            ReactiveMenuSession<CodexState> another = codex.open(second);
            assertTrue(another.state().lorePages.isEmpty());
            assertEquals(Integer.valueOf(1), ui.state().lorePages.get("apple"));
        }
    }

    private static final class CodexBrowse {
        final MenuRenderer menus;
        final AtomicInteger scans = new AtomicInteger();
        CodexBrowse(MenuRenderer menus) { this.menus = menus; }
        ReactiveMenuSession<CodexState> open(Player player) {
            scans.incrementAndGet(); // Unlock/catalog scanning is an explicit domain step, never in render.
            List<String> visible = Arrays.asList("apple", "pear");
            return menus.openReactive(player, "codex", new CodexState(visible, 0, Collections.<String, Integer>emptyMap()), this::render);
        }
        MenuModel render(ReactiveMenuSession<CodexState> ui) {
            CodexState state = ui.state();
            Page<String> page = new Paginator<String>(state.visible, 2).page(state.page);
            MenuTemplate.Builder menu = MenuTemplate.builder("Codex", 1);
            int slot = 0;
            for (String id : page.values()) {
                int lore = state.lorePages.containsKey(id) ? state.lorePages.get(id).intValue() : 0;
                menu.slot(slot++, MenuEntry.of(MenuTestEnvironment.icon(Material.PAPER, 1, id + " lore " + lore),
                        click -> ui.update(current -> current.nextLore(id))));
            }
            menu.slot(8, MenuEntry.of(MenuTestEnvironment.icon(Material.BARRIER, 1, "back"),
                    click -> ui.navigate(() -> ui.viewer().closeInventory())));
            return MenuCompiler.compile(menu.build());
        }
    }
    private static final class CodexState {
        final List<String> visible;
        final int page;
        final Map<String, Integer> lorePages;
        CodexState(List<String> visible, int page, Map<String, Integer> lorePages) {
            this.visible = Collections.unmodifiableList(new java.util.ArrayList<String>(visible));
            this.page = page;
            this.lorePages = Collections.unmodifiableMap(new HashMap<String, Integer>(lorePages));
        }
        CodexState nextLore(String id) {
            Map<String, Integer> next = new HashMap<String, Integer>(lorePages);
            next.put(id, Integer.valueOf((next.containsKey(id) ? next.get(id).intValue() : 0) + 1));
            return new CodexState(visible, page, next);
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof CodexState)) return false;
            CodexState state = (CodexState) other;
            return page == state.page && visible.equals(state.visible) && lorePages.equals(state.lorePages);
        }
        @Override public int hashCode() { return Objects.hash(visible, page, lorePages); }
    }
}
