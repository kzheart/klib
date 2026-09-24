package me.kzheart.klib.item;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalItemsTest {
    private final ClassLoader loader = getClass().getClassLoader();
    private final ExternalItems items = ExternalItems.of(
            ExternalItemSources.mmoItems(loader),
            ExternalItemSources.neigeItems(loader),
            ExternalItemSources.itemsAdder(loader));

    @Test
    void createsItemsThroughReflectiveAdapters() {
        ItemStack blade = items.create("mi:SWORD:FLAME").get();
        assertEquals(Material.DIAMOND_SWORD, blade.getType());
        assertEquals(3, items.create("ni:heal_potion", 3).get().getAmount());
        assertEquals(Material.EMERALD, items.create("ia:ruby:gem").get().getType());

        assertFalse(items.create("mi:SWORD:MISSING").isPresent());
        assertThrows(IllegalArgumentException.class, () -> items.create("mi:NO_SEPARATOR"));
    }

    @Test
    void identifiesAndMatchesByUnifiedReference() {
        ItemStack potion = items.create("ni:heal_potion").get();
        assertEquals(ItemRef.of("ni", "heal_potion"), items.identify(potion).get());
        assertEquals("ia:ruby:gem", items.identify(items.create("ia:ruby:gem").get()).get().toString());

        assertTrue(items.matches("ni:heal_potion", potion));
        assertFalse(items.matches("mi:SWORD:FLAME", potion));
        assertFalse(items.identify(new ItemStack(Material.STONE)).isPresent());
    }

    @Test
    void vanillaReferenceIgnoresCustomItemsOfTheSameMaterial() {
        Predicate<ItemStack> sword = items.matcher("DIAMOND_SWORD");
        assertTrue(sword.test(new ItemStack(Material.DIAMOND_SWORD)));
        assertFalse(sword.test(items.create("mi:SWORD:FLAME").get()));
        assertTrue(items.matches("minecraft:diamond_sword", new ItemStack(Material.DIAMOND_SWORD)));
    }

    @Test
    void unknownPrefixOrMaterialFailsFast() {
        assertThrows(IllegalArgumentException.class, () -> items.parse("xx:thing"));
        assertThrows(IllegalArgumentException.class, () -> items.parse("NOT_A_MATERIAL"));
        assertEquals(ItemRef.of("mm", "Boss"), items.parse("mm:Boss"));
        assertFalse(items.create("mm:Boss").isPresent());
    }

    @Test
    void specDelegatesMatchingAndCreationToTheRegistry() {
        FakeItemServer.install(false);
        ItemSpec spec = items.spec("mi:SWORD:FLAME");
        ItemStack created = spec.create();
        assertTrue(spec.matches(created));
        assertFalse(spec.matches(new ItemStack(Material.DIAMOND_SWORD)));
    }

    @Test
    void detectReportsAvailableMissingAndFailedSources() {
        List<String> enabled = Arrays.asList("MMOItems", "ItemsAdder", "MythicMobs");
        PluginManager manager = (PluginManager) Proxy.newProxyInstance(loader, new Class<?>[]{PluginManager.class},
                (proxy, method, arguments) -> {
                    if (!"getPlugin".equals(method.getName()) || !enabled.contains(arguments[0])) {
                        return null;
                    }
                    return Proxy.newProxyInstance(loader, new Class<?>[]{Plugin.class},
                            (p, m, a) -> "isEnabled".equals(m.getName()) ? Boolean.TRUE : null);
                });

        ExternalItems detected = ExternalItems.detect(manager);
        List<ExternalItems.SourceState> report = detected.report();
        assertEquals(ExternalItems.Status.AVAILABLE, report.get(0).status());
        assertEquals(ExternalItems.Status.MISSING, report.get(1).status());
        assertEquals(ExternalItems.Status.AVAILABLE, report.get(2).status());
        assertEquals(ExternalItems.Status.FAILED, report.get(3).status());
        assertTrue(report.get(3).detail().contains("Class not found"), report.get(3).detail());
        assertTrue(detected.available("mi"));
        assertFalse(detected.available("mm"));
    }
}
