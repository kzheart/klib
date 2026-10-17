package me.kzheart.klib.item

import org.bukkit.inventory.ItemStack
import java.util.Locale
import java.util.Objects
import java.util.Optional

/** 类型化标签的校验、读取和存储转换；Java 声明层保留 T 的平台返回契约。 */
internal class TagKeyValue<T> private constructor(rawKey: String, private val type: TagValueType<T>, private val bridge: ItemTagBridge) {
    private val key = validate(rawKey)
    fun using(replacement: ItemTagBridge): TagKeyValue<T> = TagKeyValue(key, type, replacement)
    fun value(): String = key
    fun type(): Class<T> = type.javaType()
    fun get(item: ItemStack, owner: TagKey<T>): T? = bridge.get(item, owner)
    @Suppress("UNCHECKED_CAST")
    fun find(item: ItemStack, owner: TagKey<T>): Optional<T> = Optional.ofNullable(get(item, owner)) as Optional<T>
    fun getOrDefault(item: ItemStack, owner: TagKey<T>, fallback: T?): T? = get(item, owner) ?: fallback
    fun set(item: ItemStack, owner: TagKey<T>, value: T) { bridge.set(item, owner, Objects.requireNonNull(value, "value")) }
    fun has(item: ItemStack, owner: TagKey<T>): Boolean = bridge.has(item, owner)
    fun remove(item: ItemStack, owner: TagKey<T>) { bridge.remove(item, owner) }
    // Java 桥接实现仍使用稳定的原始方法名。
    @JvmName("valueType") internal fun valueType(): TagValueType<T> = type
    @JvmName("toStorage") internal fun toStorage(value: T): Any? = if (type is BooleanTagValueType) (if (value == true) 1 else 0).toByte() else value
    @Suppress("UNCHECKED_CAST")
    @JvmName("fromStorage") internal fun fromStorage(value: Any?): T? = when {
        value == null -> null
        type is BooleanTagValueType -> ((value as Number).toByte() != 0.toByte()) as T
        else -> type.javaType().cast(value)
    }
    override fun equals(other: Any?): Boolean = other is TagKeyValue<*> && key == other.key && type.javaType() == other.type.javaType()
    override fun hashCode(): Int = 31 * key.hashCode() + type.javaType().hashCode()
    override fun toString(): String = "TagKey{$key:${type.javaType().simpleName}}"
    private class BooleanTagValueType : TagValueType<Boolean>(Boolean::class.javaObjectType, "BYTE", "Byte")
    companion object {
        @JvmStatic fun string(key: String): TagKeyValue<String> = create(key, TagValueType.STRING)
        @JvmStatic fun integer(key: String): TagKeyValue<Int> = create(key, TagValueType.INTEGER)
        @JvmStatic fun longValue(key: String): TagKeyValue<Long> = create(key, TagValueType.LONG)
        @JvmStatic fun doubleValue(key: String): TagKeyValue<Double> = create(key, TagValueType.DOUBLE)
        @JvmStatic fun bool(key: String): TagKeyValue<Boolean> = TagKeyValue(key, BooleanTagValueType(), AdaptiveItemTagBridge.createDefault())
        @JvmStatic fun bytes(key: String): TagKeyValue<ByteArray> = create(key, TagValueType.BYTE_ARRAY)
        private fun <T> create(key: String, type: TagValueType<T>): TagKeyValue<T> = TagKeyValue(key, type, AdaptiveItemTagBridge.createDefault())
        private fun validate(raw: String): String {
            val normalized = raw.lowercase(Locale.ROOT)
            require(normalized.matches(Regex("[a-z0-9._-]+:[a-z0-9/._-]+"))) { "Tag key must be namespace:path: $raw" }
            return normalized
        }
    }
}
