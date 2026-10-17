package me.kzheart.klib.item

import org.bukkit.inventory.ItemStack
import java.util.Locale
import java.util.Objects
import java.util.Optional

/** 对调用方隐藏存储细节的类型化物品标签。 */
class TagKey<T> private constructor(rawKey: String, private val type: TagValueType<T>, private val bridge: ItemTagBridge) {
    private val key = validate(rawKey)
    fun using(replacement: ItemTagBridge): TagKey<T> = TagKey(key, type, replacement)
    fun value(): String = key
    fun type(): Class<T> = type.javaType()
    fun get(item: ItemStack): T? = bridge.get(item, this)
    @Suppress("UNCHECKED_CAST")
    fun find(item: ItemStack): Optional<T> = Optional.ofNullable(get(item)) as Optional<T>
    fun getOrDefault(item: ItemStack, fallback: T?): T? = get(item) ?: fallback
    fun set(item: ItemStack, value: T) { bridge.set(item, this, Objects.requireNonNull(value, "value")) }
    fun has(item: ItemStack): Boolean = bridge.has(item, this)
    fun remove(item: ItemStack) { bridge.remove(item, this) }
    // Java 桥接实现仍使用稳定的原始方法名。
    @JvmName("valueType") internal fun valueType(): TagValueType<T> = type
    @JvmName("toStorage") internal fun toStorage(value: T): Any? = if (type is BooleanTagValueType) (if (value == true) 1 else 0).toByte() else value
    @Suppress("UNCHECKED_CAST")
    @JvmName("fromStorage") internal fun fromStorage(value: Any?): T? = when {
        value == null -> null
        type is BooleanTagValueType -> ((value as Number).toByte() != 0.toByte()) as T
        else -> type.javaType().cast(value)
    }
    override fun equals(other: Any?): Boolean = other is TagKey<*> && key == other.key && type.javaType() == other.type.javaType()
    override fun hashCode(): Int = 31 * key.hashCode() + type.javaType().hashCode()
    override fun toString(): String = "TagKey{$key:${type.javaType().simpleName}}"
    private class BooleanTagValueType : TagValueType<Boolean>(Boolean::class.javaObjectType, "BYTE", "Byte")
    companion object {
        @JvmStatic fun string(key: String): TagKey<String> = create(key, TagValueType.STRING)
        @JvmStatic fun integer(key: String): TagKey<Int> = create(key, TagValueType.INTEGER)
        @JvmStatic fun longValue(key: String): TagKey<Long> = create(key, TagValueType.LONG)
        @JvmStatic fun doubleValue(key: String): TagKey<Double> = create(key, TagValueType.DOUBLE)
        @JvmStatic fun bool(key: String): TagKey<Boolean> = TagKey(key, BooleanTagValueType(), AdaptiveItemTagBridge.createDefault())
        @JvmStatic fun bytes(key: String): TagKey<ByteArray> = create(key, TagValueType.BYTE_ARRAY)
        private fun <T> create(key: String, type: TagValueType<T>): TagKey<T> = TagKey(key, type, AdaptiveItemTagBridge.createDefault())
        private fun validate(raw: String): String {
            val normalized = raw.lowercase(Locale.ROOT)
            require(normalized.matches(Regex("[a-z0-9._-]+:[a-z0-9/._-]+"))) { "Tag key must be namespace:path: $raw" }
            return normalized
        }
    }
}
