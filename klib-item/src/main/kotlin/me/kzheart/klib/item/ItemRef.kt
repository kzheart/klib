package me.kzheart.klib.item

/** 形如 prefix:id 的外部物品引用。 */
class ItemRef private constructor(private val prefix: String, private val id: String) {
    fun prefix(): String = prefix
    fun id(): String = id
    fun vanilla(): Boolean = prefix == VANILLA
    override fun equals(other: Any?): Boolean = other is ItemRef && prefix == other.prefix && id == other.id
    override fun hashCode(): Int = 31 * prefix.hashCode() + id.hashCode()
    override fun toString(): String = "$prefix:$id"
    companion object {
        const val VANILLA = "minecraft"
        @JvmStatic fun of(prefix: String, id: String): ItemRef {
            val validPrefix = requirePrefix(prefix)
            require(id.trim().isNotEmpty()) { "Item id must not be blank" }
            return ItemRef(validPrefix, id)
        }
        @JvmStatic @JvmName("requirePrefix") internal fun requirePrefix(prefix: String): String {
            require(prefix.matches(Regex("[a-z0-9_-]+"))) { "Invalid item source prefix: '$prefix'" }
            return prefix
        }
    }
}
