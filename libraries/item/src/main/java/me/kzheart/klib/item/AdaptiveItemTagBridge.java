package me.kzheart.klib.item;

import org.bukkit.inventory.ItemStack;
import java.util.function.BooleanSupplier;

/** Java 泛型平台返回契约；存储与迁移逻辑由 Kotlin 实现。
 * 旧 Kotlin 调用 val value: String = bridge.get(item, key) 依赖平台泛型；缺失标签仍返回 null。
 */
public final class AdaptiveItemTagBridge implements ItemTagBridge {
    private final AdaptiveItemTagBridgeState state;
    public AdaptiveItemTagBridge(PdcItemTagBridge pdc, LegacyNbtItemTagBridge legacy) {
        state = new AdaptiveItemTagBridgeState(pdc, legacy);
    }
    AdaptiveItemTagBridge(ItemTagBridge pdc, ItemTagBridge legacy, BooleanSupplier pdcSupported, BooleanSupplier legacySupported) {
        state = new AdaptiveItemTagBridgeState(pdc, legacy, pdcSupported, legacySupported);
    }
    static AdaptiveItemTagBridge createDefault() { return new AdaptiveItemTagBridge(new PdcItemTagBridge(), new LegacyNbtItemTagBridge()); }
    @Override public <T> T get(ItemStack item, TagKey<T> key) { return state.get(item, key); }
    @Override public <T> void set(ItemStack item, TagKey<T> key, T value) { state.set(item, key, value); }
    @Override public boolean has(ItemStack item, TagKey<?> key) { return state.has(item, key); }
    @Override public void remove(ItemStack item, TagKey<?> key) { state.remove(item, key); }
}
