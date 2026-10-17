package me.kzheart.klib.item

import org.bukkit.inventory.meta.ItemMeta
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/** 按实现类身份缓存反射句柄；ClassValue 不会阻止插件类加载器卸载。 */
internal object ItemMetaReflection {
    private val methods = object : ClassValue<ConcurrentHashMap<String, Optional<Method>>>() {
        override fun computeValue(type: Class<*>): ConcurrentHashMap<String, Optional<Method>> = ConcurrentHashMap()
    }
    @JvmStatic fun supports(meta: ItemMeta, name: String, vararg parameters: Class<*>): Boolean = find(meta.javaClass, name, parameters).isPresent
    @JvmStatic fun invokeIfPresent(meta: ItemMeta, name: String, parameters: Array<out Class<*>>, vararg arguments: Any?): Boolean {
        val method = find(meta.javaClass, name, parameters).orElse(null) ?: return false
        invoke(method, meta, arguments)
        return true
    }
    @JvmStatic fun invokeOrNull(meta: ItemMeta, name: String): Any? =
        find(meta.javaClass, name, emptyArray()).orElse(null)?.let { invoke(it, meta, emptyArray()) }
    private fun invoke(method: Method, target: Any, arguments: Array<out Any?>): Any? = try { method.invoke(target, *arguments) }
        catch (failure: IllegalAccessException) { throw IllegalStateException("Cannot access $method", failure) }
        catch (failure: InvocationTargetException) {
            when (val cause = failure.cause) {
                is RuntimeException -> throw cause
                is Error -> throw cause
                else -> throw IllegalStateException("$method failed", cause)
            }
        }
    @JvmStatic private fun find(type: Class<*>, name: String, parameters: Array<out Class<*>>): Optional<Method> =
        methods.get(type).computeIfAbsent(name + parameters.joinToString("") { ",${it.name}" }) {
            try { Optional.of(type.getMethod(name, *parameters).apply { isAccessible = true }) }
            catch (_: NoSuchMethodException) { Optional.empty() }
            catch (_: SecurityException) { Optional.empty() }
        }
}
