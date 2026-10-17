package net.momirealms.craftengine.bukkit.api;

import java.util.HashMap;
import java.util.Map;
import net.momirealms.craftengine.bukkit.item.BukkitItemDefinition;
import org.bukkit.inventory.ItemStack;

/** 测试替身：仅保留官方公开入口形状，不模拟完整 CraftEngine 服务端。 */
public final class CraftEngineItems {
    public static final Map<String, BukkitItemDefinition> DEFINITIONS = new HashMap<>();
    public static final Map<Short, String> IDENTITIES = new HashMap<>();
    public static int lookups;
    public static int registryReads;
    public static RuntimeException lookupFailure;

    private CraftEngineItems() { }

    public static BukkitItemDefinition byId(String id) {
        lookups++;
        if (lookupFailure != null) throw lookupFailure;
        return DEFINITIONS.get(id);
    }

    public static Map<String, BukkitItemDefinition> loadedItems() {
        registryReads++;
        return DEFINITIONS;
    }

    @SuppressWarnings("deprecation")
    public static Id getCustomItemId(ItemStack item) {
        String id = IDENTITIES.get(item.getDurability());
        return id == null ? null : new Id(id);
    }

    public static void reset() {
        DEFINITIONS.clear();
        IDENTITIES.clear();
        lookups = 0;
        registryReads = 0;
        lookupFailure = null;
    }

    public static final class Id {
        private final String value;
        private Id(String value) { this.value = value; }
        @Override public String toString() { return value; }
    }
}
