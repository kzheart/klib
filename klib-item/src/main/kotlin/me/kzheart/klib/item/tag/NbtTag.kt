package me.kzheart.klib.item.tag

import java.util.Collections

/** 标准 NBT 类型；END 只用于二进制结束标记，不是可存储的值。 */
enum class NbtType(val id: Int) {
    BYTE(1), SHORT(2), INT(3), LONG(4), FLOAT(5), DOUBLE(6), BYTE_ARRAY(7),
    STRING(8), LIST(9), COMPOUND(10), INT_ARRAY(11), LONG_ARRAY(12)
}

/** 与服务端实现无关的标签值。数组在输入、输出和复制时均隔离。 */
sealed class NbtTag(val type: NbtType) {
    abstract fun copy(): NbtTag

    /** 标量或数组的 Java 值；复合值使用 [NbtCompound] / [NbtList] 的类型化接口。 */
    open fun value(): Any = throw IllegalStateException("$type is not a scalar tag")

    companion object {
        @JvmStatic fun byteValue(value: Byte): NbtTag = Scalar(NbtType.BYTE, value)
        @JvmStatic fun shortValue(value: Short): NbtTag = Scalar(NbtType.SHORT, value)
        @JvmStatic fun intValue(value: Int): NbtTag = Scalar(NbtType.INT, value)
        @JvmStatic fun longValue(value: Long): NbtTag = Scalar(NbtType.LONG, value)
        @JvmStatic fun floatValue(value: Float): NbtTag = Scalar(NbtType.FLOAT, value)
        @JvmStatic fun doubleValue(value: Double): NbtTag = Scalar(NbtType.DOUBLE, value)
        @JvmStatic fun string(value: String): NbtTag = Scalar(NbtType.STRING, value)
        @JvmStatic fun bytes(value: ByteArray): NbtTag = Scalar(NbtType.BYTE_ARRAY, value.copyOf())
        @JvmStatic fun ints(value: IntArray): NbtTag = Scalar(NbtType.INT_ARRAY, value.copyOf())
        @JvmStatic fun longs(value: LongArray): NbtTag = Scalar(NbtType.LONG_ARRAY, value.copyOf())

        /** 接受精确的 NBT 标量类型；不隐式缩窄数字，也不把 null 当作删除。 */
        @JvmStatic fun from(value: Any): NbtTag = when (value) {
            is Byte -> byteValue(value)
            is Short -> shortValue(value)
            is Int -> intValue(value)
            is Long -> longValue(value)
            is Float -> floatValue(value)
            is Double -> doubleValue(value)
            is String -> string(value)
            is ByteArray -> bytes(value)
            is IntArray -> ints(value)
            is LongArray -> longs(value)
            is NbtTag -> value.copy()
            else -> throw IllegalArgumentException("Not an NBT value: ${value.javaClass.name}")
        }
    }

    private class Scalar(type: NbtType, private val stored: Any) : NbtTag(type) {
        override fun value(): Any = when (stored) {
            is ByteArray -> stored.copyOf()
            is IntArray -> stored.copyOf()
            is LongArray -> stored.copyOf()
            else -> stored
        }
        override fun copy(): NbtTag = from(value())
        override fun equals(other: Any?): Boolean = other is Scalar && type == other.type && when (stored) {
            is ByteArray -> stored.contentEquals(other.stored as ByteArray)
            is IntArray -> stored.contentEquals(other.stored as IntArray)
            is LongArray -> stored.contentEquals(other.stored as LongArray)
            else -> stored == other.stored
        }
        override fun hashCode(): Int = 31 * type.hashCode() + when (stored) {
            is ByteArray -> stored.contentHashCode()
            is IntArray -> stored.contentHashCode()
            is LongArray -> stored.contentHashCode()
            else -> stored.hashCode()
        }
        override fun toString(): String = "$type($stored)"
    }
}

/** 可编辑的复合标签。取得的值属于本对象；[copy] 提供完全独立的快照。 */
class NbtCompound @JvmOverloads constructor(values: Map<String, NbtTag> = emptyMap()) : NbtTag(NbtType.COMPOUND) {
    private val tags = LinkedHashMap<String, NbtTag>().apply { values.forEach { (key, value) -> put(key, value.copy()) } }
    operator fun get(key: String): NbtTag? = tags[key]
    operator fun set(key: String, value: NbtTag) { tags[key] = value.copy() }
    fun put(key: String, value: NbtTag): NbtCompound = apply { set(key, value) }
    fun remove(key: String): NbtTag? = tags.remove(key)
    fun contains(key: String): Boolean = tags.containsKey(key)
    fun isEmpty(): Boolean = tags.isEmpty()
    fun size(): Int = tags.size
    fun keys(): Set<String> = Collections.unmodifiableSet(LinkedHashSet(tags.keys))
    fun entries(): Map<String, NbtTag> = Collections.unmodifiableMap(LinkedHashMap(tags))
    override fun copy(): NbtCompound = NbtCompound(tags)
    override fun equals(other: Any?): Boolean = other is NbtCompound && tags == other.tags
    override fun hashCode(): Int = tags.hashCode()
    override fun toString(): String = tags.toString()
}

/** 跨版本同型列表。空列表无元素类型，不接受混合类型列表。 */
class NbtList @JvmOverloads constructor(values: List<NbtTag> = emptyList()) : NbtTag(NbtType.LIST), Iterable<NbtTag> {
    private val tags = values.map { it.copy() }
    init { require(tags.all { it.type == tags.firstOrNull()?.type }) { "NBT list elements must have the same type" } }
    fun elementType(): NbtType? = tags.firstOrNull()?.type
    fun size(): Int = tags.size
    operator fun get(index: Int): NbtTag = tags[index]
    fun values(): List<NbtTag> = Collections.unmodifiableList(tags)
    override fun iterator(): Iterator<NbtTag> = values().iterator()
    override fun copy(): NbtList = NbtList(tags)
    override fun equals(other: Any?): Boolean = other is NbtList && tags == other.tags
    override fun hashCode(): Int = tags.hashCode()
    companion object {
        @JvmStatic fun of(vararg values: NbtTag): NbtList = NbtList(values.asList())
    }
}
