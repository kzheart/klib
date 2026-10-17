package me.kzheart.klib.item.tag

import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID

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

    /** 精确类型读取，不做数字缩窄、字符串解析或其他隐式转换。 */
    fun asByte(): Byte { requireType(NbtType.BYTE); return value() as Byte }
    fun asShort(): Short { requireType(NbtType.SHORT); return value() as Short }
    fun asInt(): Int { requireType(NbtType.INT); return value() as Int }
    fun asLong(): Long { requireType(NbtType.LONG); return value() as Long }
    fun asFloat(): Float { requireType(NbtType.FLOAT); return value() as Float }
    fun asDouble(): Double { requireType(NbtType.DOUBLE); return value() as Double }
    fun asString(): String { requireType(NbtType.STRING); return value() as String }
    fun asByteArray(): ByteArray { requireType(NbtType.BYTE_ARRAY); return value() as ByteArray }
    fun asIntArray(): IntArray { requireType(NbtType.INT_ARRAY); return value() as IntArray }
    fun asLongArray(): LongArray { requireType(NbtType.LONG_ARRAY); return value() as LongArray }
    fun asCompound(): NbtCompound { requireType(NbtType.COMPOUND); return this as NbtCompound }
    fun asList(): NbtList { requireType(NbtType.LIST); return this as NbtList }

    /** 显式选择数值转换；返回原始数值类型，由调用方决定是否缩窄，不解析字符串。 */
    fun asNumber(): Number = when (type) {
        NbtType.BYTE, NbtType.SHORT, NbtType.INT, NbtType.LONG, NbtType.FLOAT, NbtType.DOUBLE -> value() as Number
        else -> throw IllegalStateException("Expected numeric NBT type but found $type")
    }

    /** 按 NBT 惯例读取 BYTE：零为 false，非零为 true；不接受字符串或其他数字类型。 */
    fun asBoolean(): Boolean = asByte() != 0.toByte()

    /** UUID 显式编码为标准字符串；拒绝缩短分组等非规范表示。 */
    fun asUuid(): UUID {
        val value = asString()
        val uuid = UUID.fromString(value)
        require(uuid.toString().equals(value, ignoreCase = true)) { "Invalid canonical UUID string: $value" }
        return uuid
    }

    private fun requireType(expected: NbtType) {
        check(type == expected) { "Expected NBT $expected but found $type" }
    }

    companion object {
        private const val MAX_CONVERSION_DEPTH = 512

        @JvmStatic fun booleanValue(value: Boolean): NbtTag = byteValue(if (value) 1 else 0)
        @JvmStatic fun uuidValue(value: UUID): NbtTag = string(value.toString())
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

        /** 显式递归转换；键必须是字符串，不接受 null、混型或循环容器；根深度为 0，最大深度为 512。 */
        @JvmStatic fun ofMap(values: Map<*, *>): NbtCompound = convert(values, identitySet(), 0).asCompound()

        /** 显式递归同型转换；保留精确数值类型，包含嵌入 NbtTag 的子树在内，根深度为 0、最大为 512。 */
        @JvmStatic fun ofList(values: List<*>): NbtList = convert(values, identitySet(), 0).asList()

        private fun identitySet(): MutableSet<Any> = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())

        private fun convert(value: Any?, ancestors: MutableSet<Any>, depth: Int): NbtTag {
            require(depth <= MAX_CONVERSION_DEPTH) { "NBT conversion nesting exceeds $MAX_CONVERSION_DEPTH" }
            // 已有标签也遍历验证，不能通过嵌入标签子树绕过此次转换的深度上限。
            val input = when (value) {
                is NbtCompound -> value.entries()
                is NbtList -> value.values()
                else -> value
            }
            return when (input) {
                is Map<*, *>, is List<*> -> {
                    require(ancestors.add(input)) { "Cyclic NBT container" }
                    try {
                        when (input) {
                            is Map<*, *> -> NbtCompound(input.entries.associate { (key, child) ->
                                require(key is String) { "NBT compound keys must be strings: ${key?.javaClass?.name ?: "null"}" }
                                key to convert(child, ancestors, depth + 1)
                            })
                            else -> NbtList((input as List<*>).map { convert(it, ancestors, depth + 1) })
                        }
                    } finally {
                        ancestors.remove(input)
                    }
                }
                null -> throw IllegalArgumentException("NBT values must not be null")
                else -> from(input)
            }
        }

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

    /** 缺失返回 null；默认重载直接返回传入默认值。已存在但类型不匹配时抛异常，不使用默认值。 */
    fun getBoolean(key: String): Boolean? = tags[key]?.asBoolean()
    fun getBoolean(key: String, defaultValue: Boolean): Boolean = getBoolean(key) ?: defaultValue
    fun getUuid(key: String): UUID? = tags[key]?.asUuid()
    fun getUuid(key: String, defaultValue: UUID): UUID = getUuid(key) ?: defaultValue
    fun getByte(key: String): Byte? = tags[key]?.asByte()
    fun getByte(key: String, defaultValue: Byte): Byte = getByte(key) ?: defaultValue
    fun getShort(key: String): Short? = tags[key]?.asShort()
    fun getShort(key: String, defaultValue: Short): Short = getShort(key) ?: defaultValue
    fun getInt(key: String): Int? = tags[key]?.asInt()
    fun getInt(key: String, defaultValue: Int): Int = getInt(key) ?: defaultValue
    fun getLong(key: String): Long? = tags[key]?.asLong()
    fun getLong(key: String, defaultValue: Long): Long = getLong(key) ?: defaultValue
    fun getFloat(key: String): Float? = tags[key]?.asFloat()
    fun getFloat(key: String, defaultValue: Float): Float = getFloat(key) ?: defaultValue
    fun getDouble(key: String): Double? = tags[key]?.asDouble()
    fun getDouble(key: String, defaultValue: Double): Double = getDouble(key) ?: defaultValue
    fun getString(key: String): String? = tags[key]?.asString()
    fun getString(key: String, defaultValue: String): String = getString(key) ?: defaultValue
    fun getByteArray(key: String): ByteArray? = tags[key]?.asByteArray()
    fun getByteArray(key: String, defaultValue: ByteArray): ByteArray = getByteArray(key) ?: defaultValue
    fun getIntArray(key: String): IntArray? = tags[key]?.asIntArray()
    fun getIntArray(key: String, defaultValue: IntArray): IntArray = getIntArray(key) ?: defaultValue
    fun getLongArray(key: String): LongArray? = tags[key]?.asLongArray()
    fun getLongArray(key: String, defaultValue: LongArray): LongArray = getLongArray(key) ?: defaultValue
    fun getCompound(key: String): NbtCompound? = tags[key]?.asCompound()
    fun getCompound(key: String, defaultValue: NbtCompound): NbtCompound = getCompound(key) ?: defaultValue
    fun getList(key: String): NbtList? = tags[key]?.asList()
    fun getList(key: String, defaultValue: NbtList): NbtList = getList(key) ?: defaultValue

    fun putBoolean(key: String, value: Boolean): NbtCompound = put(key, NbtTag.booleanValue(value))
    fun putUuid(key: String, value: UUID): NbtCompound = put(key, NbtTag.uuidValue(value))
    fun putByte(key: String, value: Byte): NbtCompound = put(key, NbtTag.byteValue(value))
    fun putShort(key: String, value: Short): NbtCompound = put(key, NbtTag.shortValue(value))
    fun putInt(key: String, value: Int): NbtCompound = put(key, NbtTag.intValue(value))
    fun putLong(key: String, value: Long): NbtCompound = put(key, NbtTag.longValue(value))
    fun putFloat(key: String, value: Float): NbtCompound = put(key, NbtTag.floatValue(value))
    fun putDouble(key: String, value: Double): NbtCompound = put(key, NbtTag.doubleValue(value))
    fun putString(key: String, value: String): NbtCompound = put(key, NbtTag.string(value))
    fun putByteArray(key: String, value: ByteArray): NbtCompound = put(key, NbtTag.bytes(value))
    fun putIntArray(key: String, value: IntArray): NbtCompound = put(key, NbtTag.ints(value))
    fun putLongArray(key: String, value: LongArray): NbtCompound = put(key, NbtTag.longs(value))
    fun putCompound(key: String, value: NbtCompound): NbtCompound = put(key, value)
    fun putList(key: String, value: NbtList): NbtCompound = put(key, value)

    /**
     * 显式路径：点分非空键与 [index] 非负列表下标；反斜线只能转义 . [ ] 和反斜线。
     * 最多 512 个键或下标段，这是相对路径预算，不保证插入子树的整体二进制深度。
     * 空键只能通过普通 [get] / [put] 访问。非法语法或超长路径抛 [IllegalArgumentException]。
     * 缺失键返回 null；已存在节点类型冲突抛 [IllegalStateException]；列表越界抛 [IndexOutOfBoundsException]。
     */
    fun getPath(path: String): NbtTag? = NbtPath.get(this, path)
    fun containsPath(path: String): Boolean = getPath(path) != null

    /**
     * 创建缺失的复合层级；需要创建列表时抛 [NoSuchElementException]，现有列表不扩展。
     * 沿用 [getPath] 的语法与容器检查；包括列表类型不匹配在内的任何失败均不改动此对象。
     */
    fun putPath(path: String, value: NbtTag): NbtCompound = apply { NbtPath.put(this, path, value) }
    /** 返回移除项；缺失键返回 null，错误行为与 [getPath] 相同；移除列表元素后后续下标前移。 */
    fun removePath(path: String): NbtTag? = NbtPath.remove(this, path)

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

/** 可编辑的跨版本同型列表。空列表无元素类型；输入深复制，取得的容器元素属于本列表。 */
class NbtList @JvmOverloads constructor(values: List<NbtTag> = emptyList()) : NbtTag(NbtType.LIST), Iterable<NbtTag> {
    private val tags = ArrayList<NbtTag>()
    init {
        require(values.all { it.type == values.firstOrNull()?.type }) { "NBT list elements must have the same type" }
        values.forEach { tags.add(it.copy()) }
    }
    fun elementType(): NbtType? = tags.firstOrNull()?.type
    fun size(): Int = tags.size
    fun isEmpty(): Boolean = tags.isEmpty()
    operator fun get(index: Int): NbtTag = tags[index]

    fun add(value: NbtTag): NbtList = apply {
        requireElementType(value)
        tags.add(value.copy())
    }

    /** 替换已存在的位置并返回旧值；即使只有一个元素，也不可通过替换改变列表类型。 */
    operator fun set(index: Int, value: NbtTag): NbtTag {
        tags[index] // 验证下标后再复制，失败不修改列表。
        requireElementType(value)
        return tags.set(index, value.copy())
    }

    fun removeAt(index: Int): NbtTag = tags.removeAt(index)
    fun clear() { tags.clear() }

    /** 只读结构视图；修改元素内的复合值仍会修改本列表。 */
    fun values(): List<NbtTag> = Collections.unmodifiableList(tags)
    override fun iterator(): Iterator<NbtTag> = values().iterator()
    override fun copy(): NbtList = NbtList(tags)
    override fun equals(other: Any?): Boolean = other is NbtList && tags == other.tags
    override fun hashCode(): Int = tags.hashCode()

    private fun requireElementType(value: NbtTag) {
        val expected = elementType()
        require(expected == null || value.type == expected) {
            "Expected NBT list element $expected but found ${value.type}"
        }
    }

    companion object {
        @JvmStatic fun of(vararg values: NbtTag): NbtList = NbtList(values.asList())
    }
}
