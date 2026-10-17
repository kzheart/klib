package me.kzheart.klib.item

import org.bukkit.inventory.ItemStack
import java.util.function.BooleanSupplier

/** 优先使用 PDC；命中原生旧标签时尝试迁移，写入成功后才删除旧值。 */
class AdaptiveItemTagBridge internal constructor(
    private val pdc: ItemTagBridge,
    private val legacy: ItemTagBridge,
    private val pdcSupported: BooleanSupplier,
    private val legacySupported: BooleanSupplier
) : ItemTagBridge {
    constructor(pdc: PdcItemTagBridge, legacy: LegacyNbtItemTagBridge) : this(
        pdc, legacy, BooleanSupplier { PdcItemTagBridge.isServerSupported() }, BooleanSupplier { legacy.supports() }
    )

    override fun <T> get(item: ItemStack, key: TagKey<T>): T? {
        if (pdcSupported.asBoolean) {
            pdc.get(item, key)?.let { return it }
            if (legacyUsable(item)) legacy.get(item, key)?.let {
                migrate(item, key, it)
                return it
            }
            return null
        }
        requireLegacy()
        return legacy.get(item, key)
    }
    override fun <T> set(item: ItemStack, key: TagKey<T>, value: T) {
        if (pdcSupported.asBoolean) pdc.set(item, key, value)
        else { requireLegacy(); legacy.set(item, key, value) }
    }
    override fun has(item: ItemStack, key: TagKey<*>): Boolean {
        if (pdcSupported.asBoolean) return pdc.has(item, key) || (legacyUsable(item) && legacy.has(item, key))
        requireLegacy()
        return legacy.has(item, key)
    }
    override fun remove(item: ItemStack, key: TagKey<*>) {
        var handled = false
        if (pdcSupported.asBoolean) { pdc.remove(item, key); handled = true }
        if (legacyUsable(item)) { legacy.remove(item, key); handled = true }
        check(handled) { "No item tag bridge is available: this server exposes neither PDC nor a supported native NBT layout for this item" }
    }
    private fun <T> migrate(item: ItemStack, key: TagKey<T>, value: T) {
        try { pdc.set(item, key, value); legacy.remove(item, key) } catch (_: RuntimeException) {
            // 尽力迁移；本次读取仍返回已读取的旧值。
        }
    }
    private fun legacyUsable(item: ItemStack): Boolean = legacySupported.asBoolean && !InventoryItems.isAir(item)
    private fun requireLegacy() {
        check(legacySupported.asBoolean) { "No item tag bridge is available: this server exposes neither PDC nor a supported native NBT layout" }
    }
    companion object {
        @JvmStatic fun createDefault(): AdaptiveItemTagBridge = AdaptiveItemTagBridge(PdcItemTagBridge(), LegacyNbtItemTagBridge())
    }
}
