package me.kzheart.klib.item

import me.kzheart.klib.item.tag.NativeItemTags
import me.kzheart.klib.item.tag.NbtTag
import org.bukkit.inventory.ItemStack

/** 保留旧入口名称；实现直接使用原生根 NBT / custom_data，不需要第三方 NBT 库。 */
class LegacyNbtItemTagBridge : ItemTagBridge {
    fun supports(): Boolean = NativeItemTags.supports()
    override fun <T> get(item: ItemStack, key: TagKey<T>): T? =
        NativeItemTags.read(item)[key.value()]?.let { key.fromStorage(it.value()) }
    override fun <T> set(item: ItemStack, key: TagKey<T>, value: T) {
        val tags = NativeItemTags.read(item)
        tags[key.value()] = NbtTag.from(requireNotNull(key.toStorage(value)))
        NativeItemTags.write(item, tags)
    }
    override fun has(item: ItemStack, key: TagKey<*>): Boolean = NativeItemTags.read(item).contains(key.value())
    override fun remove(item: ItemStack, key: TagKey<*>) {
        val tags = NativeItemTags.read(item)
        if (tags.remove(key.value()) != null) NativeItemTags.write(item, tags)
    }
}
