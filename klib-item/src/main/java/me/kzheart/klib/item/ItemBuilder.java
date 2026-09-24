package me.kzheart.klib.item;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

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

    /**
     * 设置 CustomModelData；传入 {@code null} 清除。
     * 1.14 以前的服务端没有该属性，调用会被忽略，可先用 {@link Items#supportsCustomModelData()} 判断。
     */
    public ItemBuilder customModelData(Integer value) {
        ItemMeta meta = requireMeta();
        if (ItemMetaReflection.invokeIfPresent(meta, "setCustomModelData",
                new Class<?>[]{Integer.class}, value)) {
            item.setItemMeta(meta);
        }
        return this;
    }

    public ItemBuilder unbreakable(boolean value) {
        ItemMeta meta = requireMeta();
        meta.setUnbreakable(value);
        item.setItemMeta(meta);
        return this;
    }

    /**
     * 让物品显示附魔光效而不显示附魔文本。1.20.5 起使用原生光效覆盖；
     * 更早版本添加一级 LURE 附魔并隐藏附魔标记。
     */
    public ItemBuilder glow(boolean value) {
        ItemMeta meta = requireMeta();
        if (!ItemMetaReflection.invokeIfPresent(meta, "setEnchantmentGlintOverride",
                new Class<?>[]{Boolean.class}, value ? Boolean.TRUE : null)) {
            if (value) {
                meta.addEnchant(Enchantment.LURE, 1, true);
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            } else if (meta.getEnchantLevel(Enchantment.LURE) == 1 && meta.hasItemFlag(ItemFlag.HIDE_ENCHANTS)) {
                meta.removeEnchant(Enchantment.LURE);
                meta.removeItemFlags(ItemFlag.HIDE_ENCHANTS);
            }
        }
        item.setItemMeta(meta);
        return this;
    }

    /** 设置玩家头颅的皮肤所有者；物品必须是玩家头颅，参见 {@link Items#playerHead()}。 */
    public ItemBuilder skullOwner(OfflinePlayer owner) {
        Objects.requireNonNull(owner, "owner");
        ItemMeta meta = requireMeta();
        if (!(meta instanceof SkullMeta)) {
            throw new IllegalArgumentException("Item is not a skull: " + item.getType());
        }
        ((SkullMeta) meta).setOwningPlayer(owner);
        item.setItemMeta(meta);
        return this;
    }

    public ItemBuilder skullOwner(UUID owner) {
        return skullOwner(Bukkit.getOfflinePlayer(Objects.requireNonNull(owner, "owner")));
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
