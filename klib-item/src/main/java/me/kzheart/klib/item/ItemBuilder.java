package me.kzheart.klib.item;

import org.bukkit.ChatColor;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** 可变的流式编辑器，返回独立的 ItemStack。 */
public final class ItemBuilder {
    private final ItemStack item;

    ItemBuilder(ItemStack item) {
        this.item = item;
    }

    public ItemBuilder amount(int amount) {
        if (amount < 1 || amount > item.getMaxStackSize()) {
            throw new IllegalArgumentException("Amount is outside the material stack range: " + amount);
        }
        item.setAmount(amount);
        return this;
    }

    public ItemBuilder name(String name) {
        ItemMeta meta = requireMeta();
        meta.setDisplayName(color(Objects.requireNonNull(name, "name")));
        item.setItemMeta(meta);
        return this;
    }

    public ItemBuilder lore(String... lines) {
        return lore(Arrays.asList(lines));
    }

    public ItemBuilder lore(List<String> lines) {
        Objects.requireNonNull(lines, "lines");
        List<String> colored = new ArrayList<String>(lines.size());
        for (String line : lines) {
            colored.add(color(Objects.requireNonNull(line, "lore line")));
        }
        ItemMeta meta = requireMeta();
        meta.setLore(colored);
        item.setItemMeta(meta);
        return this;
    }

    public ItemBuilder appendLore(String... lines) {
        ItemMeta meta = requireMeta();
        List<String> lore = meta.hasLore()
                ? new ArrayList<String>(meta.getLore())
                : new ArrayList<String>();
        for (String line : lines) {
            lore.add(color(Objects.requireNonNull(line, "lore line")));
        }
        meta.setLore(lore);
        item.setItemMeta(meta);
        return this;
    }

    public ItemBuilder enchant(Enchantment enchantment, int level) {
        Objects.requireNonNull(enchantment, "enchantment");
        if (level < 1) {
            throw new IllegalArgumentException("Enchantment level must be positive");
        }
        item.addUnsafeEnchantment(enchantment, level);
        return this;
    }

    public ItemBuilder flags(ItemFlag... flags) {
        ItemMeta meta = requireMeta();
        meta.addItemFlags(flags);
        item.setItemMeta(meta);
        return this;
    }

    public <T> ItemBuilder tag(TagKey<T> key, T value) {
        key.set(item, value);
        return this;
    }

    public ItemStack build() {
        return item.clone();
    }

    private ItemMeta requireMeta() {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            throw new IllegalArgumentException("Material does not support metadata: " + item.getType());
        }
        return meta;
    }

    private static String color(String value) {
        return ChatColor.translateAlternateColorCodes('&', value);
    }
}
