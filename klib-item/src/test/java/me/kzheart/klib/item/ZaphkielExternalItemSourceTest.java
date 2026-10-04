package me.kzheart.klib.item;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ZaphkielExternalItemSourceTest {
    public interface PluginApi extends Plugin { Service items(); }
    public static final class Definitions {
        public Optional<String> find(String id) { return "gem".equals(id) || "broken".equals(id) ? Optional.of(id) : Optional.empty(); }
    }
    public static final class Service {
        private int generated;
        private Player player;
        public Definitions registry() { return new Definitions(); }
        public ItemStack generateItemStack(String id, Player player) {
            if ("broken".equals(id)) throw new IllegalStateException("invalid definition");
            this.player = player;
            return new ItemStack(Material.EMERALD, 1, (short) ++generated);
        }
        public String id(ItemStack item) { return item.getType() == Material.EMERALD && item.getDurability() > 0 ? "gem" : null; }
    }

    private static PluginApi plugin(AtomicBoolean enabled, AtomicReference<Service> service) {
        return (PluginApi) Proxy.newProxyInstance(PluginApi.class.getClassLoader(), new Class<?>[]{PluginApi.class}, (p,m,a) -> {
            if ("items".equals(m.getName())) return service.get();
            if ("isEnabled".equals(m.getName())) return enabled.get();
            if ("getName".equals(m.getName())) return "ZaphkielPlus";
            throw new UnsupportedOperationException(m.getName());
        });
    }

    @Test void resolvesActualPluginPublicServiceAndRetainsPlayerContext() {
        Service service = new Service();
        ExternalItemSource source = ExternalItemSources.zaphkiel(plugin(new AtomicBoolean(true), new AtomicReference<Service>(service)));
        Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (p,m,a) -> null);
        ItemStack item = source.create("gem", player);
        assertEquals("zap", source.prefix()); assertEquals("gem", source.identify(item)); assertSame(player, service.player);
        assertNull(source.create("missing")); assertEquals(1, service.generated);
        assertThrows(IllegalStateException.class, () -> source.create("broken"));
    }

    @Test void refreshedServicesAndDisabledOwnerDoNotUseStaleState() {
        AtomicBoolean enabled = new AtomicBoolean(true); AtomicReference<Service> service = new AtomicReference<Service>(new Service());
        ExternalItemSource source = ExternalItemSources.zaphkiel(plugin(enabled, service));
        ItemStack original = source.create("gem"); Service replacement = new Service(); service.set(replacement); source.create("gem");
        assertEquals(1, replacement.generated);
        enabled.set(false); assertNull(source.create("gem")); assertNull(source.identify(new ItemStack(Material.EMERALD)));
        assertEquals(1, replacement.generated);
        assertEquals("gem", source.identify(original));
        assertFalse(ExternalItems.of(source).matches("minecraft:emerald", original));
    }

    @Test void rejectsQuantityCopyBeforeGenerationAndGeneratesDistinctBatchInstances() {
        FakeItemServer.install(false); Service service = new Service();
        ExternalItems sources = ExternalItems.of(ExternalItemSources.zaphkiel(plugin(new AtomicBoolean(true), new AtomicReference<Service>(service))));
        assertThrows(IllegalArgumentException.class, () -> sources.create("zap:gem", 2)); assertEquals(0, service.generated);
        List<ItemStack> batch = sources.createBatch("zap:gem", 3, null);
        assertEquals(3, service.generated); assertEquals(3, batch.size());
        assertEquals(1, batch.get(0).getDurability()); assertEquals(2, batch.get(1).getDurability()); assertEquals(3, batch.get(2).getDurability());
        assertThrows(UnsupportedOperationException.class, () -> batch.clear());
        assertTrue(sources.createBatch("zap:missing", 2, null).isEmpty()); assertEquals(3, service.generated);
    }

    @Test void detectsZapBeforeAnotherProviderClaimingTheSamePhysicalMaterial() {
        FakeItemServer.install(false);
        PluginApi zap = plugin(new AtomicBoolean(true), new AtomicReference<Service>(new Service()));
        Plugin ia = (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class}, (p,m,a) -> "isEnabled".equals(m.getName()) ? true : null);
        PluginManager plugins = (PluginManager) Proxy.newProxyInstance(PluginManager.class.getClassLoader(), new Class<?>[]{PluginManager.class}, (p,m,a) -> {
            if (!"getPlugin".equals(m.getName())) return null;
            return "ZaphkielPlus".equals(a[0]) ? zap : "ItemsAdder".equals(a[0]) ? ia : null;
        });
        ExternalItems sources = ExternalItems.detect(plugins);
        ItemStack item = sources.create("zap:gem").get();
        assertEquals("zap:gem", sources.identify(item).get().toString());
        assertFalse(sources.matches("minecraft:emerald", item));
    }
}
