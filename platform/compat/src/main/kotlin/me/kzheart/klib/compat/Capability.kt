package me.kzheart.klib.compat

class Capability<T> private constructor(private val id: String, private val type: Class<T>) {
    fun id(): String = id
    fun type(): Class<T> = type
    override fun equals(other: Any?): Boolean = this === other || other is Capability<*> && id == other.id && type == other.type
    override fun hashCode(): Int = 31 * id.hashCode() + type.hashCode()
    override fun toString(): String = "Capability{$id:${type.name}}"
    companion object {
        @JvmStatic fun <T> of(id: String, type: Class<T>): Capability<T> = Capability(id, type)
    }
}
