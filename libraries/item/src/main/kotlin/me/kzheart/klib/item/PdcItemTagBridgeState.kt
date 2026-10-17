package me.kzheart.klib.item

import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.ConcurrentHashMap

/** 延迟反射 PDC API，使模块在没有 PDC 类的旧服务端仍可加载。 */
internal class PdcItemTagBridgeState : ItemTagBridge {
    fun supports(item: ItemStack): Boolean = isServerSupported() && item.itemMeta != null
    override fun <T> get(item: ItemStack, key: TagKey<T>): T? = key.fromStorage(invokeContainer(item, key, Operation.GET))
    override fun <T> set(item: ItemStack, key: TagKey<T>, value: T) { invokeContainer(item, key, Operation.SET, key.toStorage(value)) }
    override fun has(item: ItemStack, key: TagKey<*>): Boolean = invokeContainer(item, key, Operation.HAS) == true
    override fun remove(item: ItemStack, key: TagKey<*>) { invokeContainer(item, key, Operation.REMOVE) }

    private fun invokeContainer(item: ItemStack, key: TagKey<*>, operation: Operation, value: Any? = null): Any? {
        val meta = item.itemMeta ?: run {
            require(operation != Operation.SET) { "Cannot write tag ${key.value()}: item ${item.type} has no metadata to store it on" }
            return null
        }
        try {
            val pdc = pdc()
            val container = pdc.getContainer.invoke(meta)
            val namespacedKey = keys.computeIfAbsent(key.value()) {
                val separator = it.indexOf(':')
                pdc.keyConstructor.newInstance(it.substring(0, separator), it.substring(separator + 1))
            }
            val dataType = types.computeIfAbsent(key.valueType().pdcField()) { pdc.typeClass.getField(it).get(null) }
            val result = when (operation) {
                Operation.GET -> pdc.get.invoke(container, namespacedKey, dataType)
                Operation.HAS -> pdc.has.invoke(container, namespacedKey, dataType)
                Operation.SET -> pdc.set.invoke(container, namespacedKey, dataType, value)
                Operation.REMOVE -> pdc.remove.invoke(container, namespacedKey)
            }
            if (operation == Operation.SET || operation == Operation.REMOVE) {
                check(item.setItemMeta(meta)) { "Bukkit rejected PersistentDataContainer metadata" }
            }
            return result
        } catch (error: InvocationTargetException) {
            throw IllegalStateException("PersistentDataContainer operation failed", error.cause)
        } catch (error: ReflectiveOperationException) {
            throw IllegalStateException("PersistentDataContainer is not available", error)
        }
    }

    private enum class Operation { GET, SET, HAS, REMOVE }
    private class Pdc {
        private val keyClass = Class.forName("org.bukkit.NamespacedKey")
        val typeClass = Class.forName(PERSISTENT_TYPE)
        private val containerClass = Class.forName("org.bukkit.persistence.PersistentDataContainer")
        val keyConstructor = keyClass.getConstructor(String::class.java, String::class.java)
        val getContainer = ItemMeta::class.java.getMethod("getPersistentDataContainer")
        val get = containerClass.getMethod("get", keyClass, typeClass)
        val has = containerClass.getMethod("has", keyClass, typeClass)
        val set = containerClass.getMethod("set", keyClass, typeClass, Any::class.java)
        val remove = containerClass.getMethod("remove", keyClass)
    }
    companion object {
        private const val PERSISTENT_TYPE = "org.bukkit.persistence.PersistentDataType"
        @Volatile private var serverSupported: Boolean? = null
        @Volatile private var pdcCache: Pdc? = null
        private val keys = ConcurrentHashMap<String, Any>()
        private val types = ConcurrentHashMap<String, Any>()
        @JvmStatic fun isServerSupported(): Boolean = serverSupported ?: run {
            val supported = try {
                Class.forName(PERSISTENT_TYPE)
                ItemMeta::class.java.getMethod("getPersistentDataContainer")
                true
            } catch (_: ClassNotFoundException) { false } catch (_: NoSuchMethodException) { false }
            serverSupported = supported
            supported
        }
        private fun pdc(): Pdc = pdcCache ?: Pdc().also { pdcCache = it }
    }
}
