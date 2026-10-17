package me.kzheart.klib.item;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.item.BukkitItemDefinition;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CraftEngineExternalItemSourceTest {
    private final AtomicBoolean enabled = new AtomicBoolean(true);
    private final AtomicBoolean ready = new AtomicBoolean(false);

    @BeforeEach void reset() {
        FakeItemServer.install(false);
        CraftEngineItems.reset();
    }

    private Plugin plugin() {
        return (Plugin) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Plugin.class},
                (proxy, method, arguments) -> "isEnabled".equals(method.getName()) ? enabled.get() : null);
    }

    private ExternalItemSource source() {
        return ExternalItemSources.craftEngine(plugin(), ready::get);
    }

    private BukkitItemDefinition define(Material material) {
        BukkitItemDefinition definition = new BukkitItemDefinition(material, (short) 123);
        CraftEngineItems.DEFINITIONS.put("example:gem", definition);
        CraftEngineItems.IDENTITIES.put((short) 123, "example:gem");
        return definition;
    }

    @Test void initialLoadingAndLegitimatelyEmptyConfigurationAreDifferentStates() {
        ExternalItems items = ExternalItems.of(source());
        assertEquals(ItemPreview.Status.NOT_READY, items.preview("ce:example:gem", null).status());
        assertFalse(items.create("ce:example:gem").isPresent());
        assertEquals(0, CraftEngineItems.lookups);

        ready.set(true);
        assertEquals(ItemPreview.Status.UNKNOWN_ID, items.preview("ce:example:gem", null).status());
        assertFalse(items.create("ce:example:gem").isPresent());
        assertEquals(0, CraftEngineItems.registryReads);
    }

    @Test void previewNeverRunsEitherRealBuildOverloadOrItemProcessors() {
        BukkitItemDefinition definition = define(Material.DIAMOND);
        ready.set(true);
        ExternalItems items = ExternalItems.of(source());
        Player player = (Player) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Player.class}, (proxy, method, arguments) -> null);

        ItemPreview withoutPlayer = items.preview("ce:example:gem", null);
        ItemPreview withPlayer = items.preview("ce:example:gem", player);
        assertEquals(ItemPreview.Status.UNSUPPORTED, withoutPlayer.status());
        assertEquals(ItemPreview.Status.UNSUPPORTED, withPlayer.status());
        assertFalse(withPlayer.item().isPresent());
        assertEquals(0, definition.builds);
        assertEquals(0, definition.playerBuilds);
        assertEquals(0, CraftEngineItems.registryReads);
    }

    @Test void explicitGenerationRetainsPlayerAndReloadUsesCurrentDefinition() {
        BukkitItemDefinition first = define(Material.DIAMOND);
        ready.set(true);
        ExternalItems items = ExternalItems.of(source());
        assertEquals(Material.DIAMOND, items.create("ce:example:gem").get().getType());
        assertEquals(1, first.builds);

        ready.set(false);
        assertFalse(items.create("ce:example:gem").isPresent());
        BukkitItemDefinition second = define(Material.EMERALD);
        ready.set(true);
        Player player = (Player) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Player.class}, (proxy, method, arguments) -> null);
        ItemStack item = items.create("ce:example:gem", 1, player).get();
        assertEquals(Material.EMERALD, item.getType());
        assertSame(player, second.lastPlayer);
        assertEquals(1, second.playerBuilds);
        assertEquals(0, second.builds);
        assertEquals(1, first.builds);
        assertEquals("ce:example:gem", items.identify(item).get().toString());
        assertTrue(items.matches("ce:example:gem", item));
        assertFalse(items.matches("minecraft:emerald", item));
    }

    @Test void disabledProviderStopsConstructionAndRetainsReadOnlyIdentity() {
        BukkitItemDefinition definition = define(Material.DIAMOND);
        ready.set(true);
        ExternalItems items = ExternalItems.of(source());
        ItemStack created = items.create("ce:example:gem").get();
        enabled.set(false);
        CraftEngineItems.DEFINITIONS.clear();
        assertEquals(ItemPreview.Status.NOT_READY, items.preview("ce:example:gem", null).status());
        assertFalse(items.create("ce:example:gem").isPresent());
        assertEquals(1, definition.builds);
        assertEquals("ce:example:gem", items.identify(created).get().toString());
        assertFalse(items.matches("minecraft:diamond", created));
        assertNull(source().identify(new ItemStack(Material.STONE)));
    }

    @Test void failuresRemainVisibleAndReferencesMustBeNamespaced() {
        ready.set(true);
        ExternalItemSource source = source();
        ExternalItems items = ExternalItems.of(source);
        IllegalStateException lookupFailure = new IllegalStateException("registry unavailable");
        CraftEngineItems.lookupFailure = lookupFailure;
        ItemPreview preview = items.preview("ce:example:gem", null);
        assertEquals(ItemPreview.Status.FAILED, preview.status());
        assertSame(lookupFailure, preview.failure().get());
        assertThrows(IllegalStateException.class, () -> items.create("ce:example:gem"));

        CraftEngineItems.lookupFailure = null;
        BukkitItemDefinition definition = define(Material.DIAMOND);
        definition.failure = new IllegalStateException("processor failed");
        assertThrows(IllegalStateException.class, () -> items.create("ce:example:gem"));
        assertEquals(ItemPreview.Status.UNSUPPORTED, items.preview("ce:example:gem", null).status());
        assertEquals(1, definition.builds);
        assertThrows(IllegalArgumentException.class, () -> source.create("gem"));
        assertThrows(IllegalArgumentException.class, () -> source.create("Example:gem"));
        assertThrows(IllegalArgumentException.class, () -> source.create("example:"));
    }

    @Test void readinessFailuresAreNotMisreportedAsUnknownItems() {
        IllegalStateException failure = new IllegalStateException("loading state failed");
        ExternalItemSource source = ExternalItemSources.craftEngine(plugin(), () -> { throw failure; });
        ItemPreview preview = ExternalItems.of(source).preview("ce:example:gem", null);
        assertEquals(ItemPreview.Status.FAILED, preview.status());
        assertSame(failure, preview.failure().get());
        assertEquals(0, CraftEngineItems.lookups);
    }

    @Test void unavailablePublicApiFailsDuringAdapterConstruction() {
        ClassLoader missingApi = new ClassLoader(getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if ("net.momirealms.craftengine.bukkit.api.CraftEngineItems".equals(name)) {
                    throw new ClassNotFoundException(name);
                }
                return super.loadClass(name, resolve);
            }
        };
        Plugin plugin = (Plugin) Proxy.newProxyInstance(missingApi, new Class<?>[]{Plugin.class},
                (proxy, method, arguments) -> "isEnabled".equals(method.getName()) ? true : null);
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> ExternalItemSources.craftEngine(plugin, () -> true));
        assertTrue(failure.getMessage().contains("CraftEngineItems"));
        assertEquals(0, CraftEngineItems.lookups);
    }
}
