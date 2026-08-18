package me.kzheart.klib.item;

import org.bukkit.inventory.ItemStack;

/** MMOItems、NeigeItems、ItemsAdder 或其他物品提供器的适配器。 */
public interface ExternalItemProvider {
    boolean matches(String provider, String id, ItemStack item);

    ItemStack create(String provider, String id);
}
