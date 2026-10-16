package me.kzheart.klib.item;

import org.bukkit.inventory.ItemStack;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * 优先使用服务端的 PDC；只有旧版 NBT API 支持当前平台时才回退读取根 NBT。
 * 命中可读取的旧版标签时会择机迁移到 PDC。
 */
public final class AdaptiveItemTagBridge implements ItemTagBridge {
    private final ItemTagBridge pdc;
    private final ItemTagBridge legacy;
    private final BooleanSupplier pdcSupported;
    private final BooleanSupplier legacySupported;

    public AdaptiveItemTagBridge(PdcItemTagBridge pdc, LegacyNbtItemTagBridge legacy) {
        this(pdc, legacy, PdcItemTagBridge::isServerSupported, legacy::supports);
    }

    AdaptiveItemTagBridge(
            ItemTagBridge pdc,
            ItemTagBridge legacy,
            BooleanSupplier pdcSupported,
            BooleanSupplier legacySupported
    ) {
        this.pdc = Objects.requireNonNull(pdc, "pdc");
        this.legacy = Objects.requireNonNull(legacy, "legacy");
        this.pdcSupported = Objects.requireNonNull(pdcSupported, "pdcSupported");
        this.legacySupported = Objects.requireNonNull(legacySupported, "legacySupported");
    }

    static AdaptiveItemTagBridge createDefault() {
        return new AdaptiveItemTagBridge(new PdcItemTagBridge(), new LegacyNbtItemTagBridge());
    }

    @Override
    public <T> T get(ItemStack item, TagKey<T> key) {
        if (pdcSupported.getAsBoolean()) {
            T value = pdc.get(item, key);
            if (value != null) {
                return value;
            }
            if (legacyUsable(item)) {
                T legacyValue = legacy.get(item, key);
                if (legacyValue != null) {
                    migrate(item, key, legacyValue);
                    return legacyValue;
                }
            }
            return null;
        }
        requireLegacy();
        return legacy.get(item, key);
    }

    @Override
    public <T> void set(ItemStack item, TagKey<T> key, T value) {
        if (pdcSupported.getAsBoolean()) {
            pdc.set(item, key, value);
            return;
        }
        requireLegacy();
        legacy.set(item, key, value);
    }

    @Override
    public boolean has(ItemStack item, TagKey<?> key) {
        if (pdcSupported.getAsBoolean()) {
            return pdc.has(item, key) || (legacyUsable(item) && legacy.has(item, key));
        }
        requireLegacy();
        return legacy.has(item, key);
    }

    @Override
    public void remove(ItemStack item, TagKey<?> key) {
        boolean handled = false;
        if (pdcSupported.getAsBoolean()) {
            pdc.remove(item, key);
            handled = true;
        }
        if (legacyUsable(item)) {
            legacy.remove(item, key);
            handled = true;
        }
        if (!handled) {
            throw new IllegalStateException("No item tag bridge is available to remove the tag:"
                    + " this server has no PersistentDataContainer support and no legacy NBT bridge"
                    + " for this item. Make sure the server exposes"
                    + " ItemMeta#getPersistentDataContainer, or the Item-NBT-API"
                    + " classes (de.tr7zw.changeme.nbtapi) support this server revision, and that the"
                    + " item is not air.");
        }
    }

    /** 将命中的旧版标签复制到 PDC，使下次读取无需再回退。 */
    private <T> void migrate(ItemStack item, TagKey<T> key, T value) {
        try {
            pdc.set(item, key, value);
            legacy.remove(item, key);
        } catch (RuntimeException ignored) {
            // 迁移尽力而为；无论迁移是否成功，都保留本次读取结果。
        }
    }

    private boolean legacyUsable(ItemStack item) {
        return legacySupported.getAsBoolean() && !InventoryItems.isAir(item);
    }

    private void requireLegacy() {
        if (!legacySupported.getAsBoolean()) {
            throw new IllegalStateException("No item tag bridge is available for this server:"
                    + " PersistentDataContainer is unavailable and Item-NBT-API"
                    + " (de.tr7zw.changeme.nbtapi) is missing or does not support this server revision."
                    + " Provide a compatible shaded Item-NBT-API, or use a server exposing"
                    + " ItemMeta#getPersistentDataContainer.");
        }
    }
}
