package me.kzheart.klib.ui;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import me.kzheart.klib.config.ConfigSnapshot;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MenuLayoutTest {
    @Test void configuredIconsAndActionsShareTheExistingModelAndCapacity() throws Exception {
        try (MenuTestEnvironment f = new MenuTestEnvironment()) {
            AtomicInteger clicks = new AtomicInteger();
            Map<String, Object> yaml = yaml("##MMB    ");
            yaml.put("items", items("#", "STONE", "B", "PAPER"));
            MenuLayout layout = load(yaml, 'M', 'B');
            assertEquals(Arrays.asList(2, 3), layout.slots('M'));
            Page<Integer> page = new Paginator<>(Arrays.asList(1, 2, 3), layout.slots('M').size()).page(1);
            assertEquals(Collections.singletonList(3), page.values());
            MenuModel model = layout.compile(b -> {
                b.entries('M', Collections.singletonList(icon(Material.DIAMOND)));
                b.action('B', click -> clicks.incrementAndGet());
            });
            assertEquals("Configured", model.title());
            assertEquals(9, model.size());
            assertEquals(Material.STONE, model.entry(0).get().item().getType());
            assertEquals(Material.DIAMOND, model.entry(2).get().item().getType());
            assertFalse(model.entry(3).isPresent());
            assertEquals("UI_BUTTON_CLICK", model.entry(4).get().clickSound().get());
            model.entry(4).get().action().accept(new MenuClick(f.player, 4));
            assertEquals(1, clicks.get());
            assertThrows(UnsupportedOperationException.class, () -> layout.slots('M').clear());
        }
    }

    @Test void everyFunctionMustBeHandledExactlyOnceAndOverflowIsNeverTruncated() {
        MenuLayout layout = load(yaml("MMB      "), 'M', 'B');
        error("bindings[B]", () -> layout.compile(b -> b.hide('M')));
        error("bindings[M]", () -> layout.compile(b -> { b.hide('M'); b.reserve('M'); }));
        error("bindings[X]", () -> layout.compile(b -> b.hide('X')));
        error("bindings[B]", () -> layout.compile(b -> b.action('B', click -> { })));
        error("capacity 2", () -> layout.compile(b -> b.entries('M', Arrays.asList(icon(Material.STONE), icon(Material.STONE), icon(Material.STONE)))));
        MenuModel empty = layout.compile(b -> { b.reserve('M'); b.hide('B'); });
        assertTrue(empty.entries().isEmpty());
        AtomicReference<MenuBindings> retained = new AtomicReference<>();
        layout.compile(b -> { retained.set(b); b.hide('M'); b.hide('B'); });
        assertThrows(IllegalStateException.class, () -> retained.get().hide('M'));
        MenuModel repeated = layout.compile(b -> { b.character('M', icon(Material.PAPER)); b.reserve('B'); });
        assertEquals(2, repeated.entries().size());
    }

    @Test void allSixRowsCanBeUsedAsListCapacity() {
        Map<String, Object> yaml = yaml("MMMMMMMMM");
        yaml.put("rows", 6); yaml.put("layout", Collections.nCopies(6, "MMMMMMMMM"));
        MenuLayout layout = load(yaml, 'M');
        assertEquals(54, layout.slots('M').size());
        MenuModel model = layout.compile(b -> b.entries('M', Collections.nCopies(54, icon(Material.PAPER))));
        assertEquals(54, model.entries().size());
    }

    @Test void loadingReportsTheExactConfigurationPath() {
        error("layout[0][2]", () -> load(yaml("MMX      "), 'M'));
        error("layout", () -> load(yaml("MM       "), 'B'));
        Map<String, Object> wrongWidth = yaml("MM");
        error("layout[0]", () -> load(wrongWidth, 'M'));
        Map<String, Object> wrongRows = yaml("MM       "); wrongRows.put("rows", 2);
        error("layout", () -> load(wrongRows, 'M'));
        Map<String, Object> badRows = yaml("MM       "); badRows.put("rows", 7);
        error("rows", () -> load(badRows, 'M'));
        Map<String, Object> badTitle = yaml("MM       "); badTitle.put("title", 5);
        error("title", () -> load(badTitle, 'M'));
        Map<String, Object> badCancel = yaml("MM       "); badCancel.put("cancel-clicks", "sometimes");
        error("cancel-clicks", () -> load(badCancel, 'M'));
        Map<String, Object> badItems = yaml("MM       "); badItems.put("items", items("XX", "STONE"));
        error("items[XX]", () -> load(badItems, 'M'));
        Map<String, Object> unresolved = yaml("#        "); unresolved.put("items", items("#", "MISSING"));
        error("items[#]", () -> load(unresolved));
        Map<String, Object> overlap = yaml("MM       "); overlap.put("slots", Collections.singletonMap("0", "STONE"));
        error("slots[0]", () -> load(overlap, 'M'));
        Map<String, Object> outside = yaml("MM       "); outside.put("slots", Collections.singletonMap("9", "STONE"));
        error("slots[9]", () -> load(outside, 'M'));
        Map<String, Object> unknown = yaml("MM       "); unknown.put("layuot", "typo");
        error("layuot", () -> load(unknown, 'M'));
    }

    @Test void candidatesAreIndependentAndFailedReloadKeepsThePublishedLayout() {
        Map<String, Object> yaml = yaml("MM       ");
        ConfigSnapshot<MenuLayout> published = new ConfigSnapshot<>(load(yaml, 'M'));
        MenuLayout original = published.value();
        yaml.put("title", "New"); yaml.put("rows", 2);
        error("layout", () -> published.prepare(() -> load(yaml, 'M')));
        assertSame(original, published.value());
        assertEquals(0, published.revision());
        assertEquals("Configured", original.compile(b -> b.hide('M')).title());
        yaml.put("layout", Arrays.asList("MMMMMMMMM", "MMMMMMMMM"));
        ConfigSnapshot.Candidate<MenuLayout> candidate = published.prepare(() -> load(yaml, 'M'));
        assertSame(original, published.value());
        published.commit(candidate);
        assertEquals(1, published.revision());
        assertEquals(18, published.value().slots('M').size());
        assertEquals("New", published.value().compile(b -> b.hide('M')).title());
        assertEquals(2, original.slots('M').size());
    }

    private static MenuLayout load(Map<String, Object> yaml, Character... characters) {
        Set<Character> functions = new LinkedHashSet<>(Arrays.asList(characters));
        return MenuLayout.from(yaml, "menus.yml:browse", functions, id ->
            icon(Material.valueOf(id)).withClickSound("UI_BUTTON_CLICK"));
    }
    private static MenuEntry icon(Material material) { return MenuEntry.of(new ItemStack(material)); }
    private static Map<String, Object> yaml(String line) {
        Map<String, Object> yaml = new LinkedHashMap<>();
        yaml.put("title", "Configured"); yaml.put("rows", 1);
        yaml.put("layout", Collections.singletonList(line)); return yaml;
    }
    private static Map<String, Object> items(String... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put(pairs[i], pairs[i + 1]);
        return result;
    }
    private static void error(String path, Runnable action) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, action::run);
        assertTrue(failure.getMessage().contains("menus.yml:browse."), failure.getMessage());
        assertTrue(failure.getMessage().contains(path), failure.getMessage());
    }
}
