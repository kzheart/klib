package me.kzheart.klib.item;

import org.bukkit.inventory.ItemStack;

/** Java 泛型平台返回契约；存储与迁移逻辑由 Kotlin 实现。
 * 旧 Kotlin 调用 val value: String = bridge.get(item, key) 依赖平台泛型；缺失标签仍返回 null。
 */
public final class PdcItemTagBridge implements ItemTagBridge {
    private final PdcItemTagBridgeState state = new PdcItemTagBridgeState();
    public PdcItemTagBridge() { }
    public boolean supports(ItemStack item) { return state.supports(item); }
    public static boolean isServerSupported() { return PdcItemTagBridgeState.isServerSupported(); }
    @Override public <T> T get(ItemStack item, TagKey<T> key) { return state.get(item, key); }
    @Override public <T> void set(ItemStack item, TagKey<T> key, T value) { state.set(item, key, value); }
    @Override public boolean has(ItemStack item, TagKey<?> key) { return state.has(item, key); }
    @Override public void remove(ItemStack item, TagKey<?> key) { state.remove(item, key); }
}
