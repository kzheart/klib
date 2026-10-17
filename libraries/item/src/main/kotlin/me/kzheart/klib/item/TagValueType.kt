package me.kzheart.klib.item

internal open class TagValueType<T>(private val javaType: Class<T>, private val pdcField: String, private val legacySuffix: String) {
    fun javaType(): Class<T> = javaType
    fun pdcField(): String = pdcField
    fun legacyGetter(): String = "get$legacySuffix"
    fun legacySetter(): String = "set$legacySuffix"
    fun valuesEqual(left: Any?, right: Any?): Boolean =
        if (left is ByteArray && right is ByteArray) left.contentEquals(right) else left == right
    companion object {
        @JvmField val STRING = TagValueType(String::class.java, "STRING", "String")
        @JvmField val INTEGER = TagValueType(Int::class.javaObjectType, "INTEGER", "Integer")
        @JvmField val LONG = TagValueType(Long::class.javaObjectType, "LONG", "Long")
        @JvmField val DOUBLE = TagValueType(Double::class.javaObjectType, "DOUBLE", "Double")
        @JvmField val BYTE = TagValueType(Byte::class.javaObjectType, "BYTE", "Byte")
        @JvmField val BYTE_ARRAY = TagValueType(ByteArray::class.java, "BYTE_ARRAY", "ByteArray")
    }
}
