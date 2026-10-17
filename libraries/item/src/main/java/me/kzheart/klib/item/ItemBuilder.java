package me.kzheart.klib.item;

import java.util.List;
import java.util.UUID;
import org.bukkit.OfflinePlayer;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;

/** 流式编辑器的 Java 平台返回契约；物品状态和编辑行为由 Kotlin 实现。
 * 旧 Kotlin 调用 val value: String = builder.tag(key) 依赖平台泛型；缺失标签仍返回 null。
 */
public final class ItemBuilder {
    private final ItemBuilderState state;
    ItemBuilder(ItemStack item) { state = new ItemBuilderState(item); }
    public ItemBuilder amount(int amount) { state.amount(amount); return this; }
    public ItemBuilder name(String name) { state.name(name); return this; }
    public ItemBuilder lore(String... lines) { state.lore(lines); return this; }
    public ItemBuilder lore(List<String> lines) { state.lore(lines); return this; }
    public ItemBuilder appendLore(String... lines) { state.appendLore(lines); return this; }
    public ItemBuilder enchant(Enchantment enchantment, int level) { state.enchant(enchantment, level); return this; }
    public ItemBuilder flags(ItemFlag... flags) { state.flags(flags); return this; }
    public ItemBuilder customModelData(Integer value) { state.customModelData(value); return this; }
    public ItemBuilder unbreakable(boolean value) { state.unbreakable(value); return this; }
    public ItemBuilder glow(boolean value) { state.glow(value); return this; }
    public ItemBuilder skullOwner(OfflinePlayer owner) { state.skullOwner(owner); return this; }
    public ItemBuilder skullOwner(UUID owner) { state.skullOwner(owner); return this; }
    public <T> ItemBuilder tag(TagKey<T> key, T value) { state.tag(key, value); return this; }
    public <T> T tag(TagKey<T> key) { return state.tag(key); }
    public ItemBuilder removeTag(TagKey<?> key) { state.removeTag(key); return this; }
    public ItemStack build() { return state.build(); }
}
