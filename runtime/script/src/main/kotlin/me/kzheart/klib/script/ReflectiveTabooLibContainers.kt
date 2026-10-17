package me.kzheart.klib.script

import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Field
import java.util.IdentityHashMap

/** All reflective method/field lookup and class-loader failures stay at the container boundary. */
class ReflectiveTabooLibContainers private constructor() {
    private class BukkitDiscovery(private val providerName: String) : TabooLibKetherInterop.ContainerDiscovery {
        private val cache = IdentityHashMap<Any, ReflectionContainer>()
        @Synchronized override fun discover(): List<TabooLibKetherInterop.OpenContainer> {
            val plugins = invoke(invokeStatic("org.bukkit.Bukkit", "getPluginManager")!!, "getPlugins")!!
            val result = ArrayList<TabooLibKetherInterop.OpenContainer>()
            for (index in 0 until ReflectArray.getLength(plugins)) {
                val plugin = ReflectArray.get(plugins, index)
                // Resolve optional method types only after excluding unrelated plugin classes.
                if (!plugin.javaClass.name.endsWith(MAIN_SUFFIX)) continue
                val name = invoke(plugin, "getName").toString()
                if (name != providerName && invoke(plugin, "isEnabled") == true) {
                    val container = cache.getOrPut(plugin) { ReflectionContainer(plugin, name) }
                    if (container.valid()) result.add(container)
                }
            }
            return result
        }
        @Synchronized override fun find(name: String?): TabooLibKetherInterop.OpenContainer? =
            discover().firstOrNull { it.name() == name }
    }

    private class ReflectionContainer(plugin: Any, private val name: String) : TabooLibKetherInterop.OpenContainer {
        private val call: Method? = try {
            Class.forName(apiName(plugin.javaClass.name), false, plugin.javaClass.classLoader)
                .getMethod("call", String::class.java, Array<Any>::class.java)
        } catch (_: ClassNotFoundException) { null } catch (_: NoSuchMethodException) { null }
        fun valid(): Boolean = call != null
        override fun name(): String = name
        override fun call(channel: String?, vararg data: Any?): TabooLibKetherInterop.OpenResult {
            val method = call ?: return TabooLibKetherInterop.OpenResult.failed()
            return try { castResult(method.invoke(null, channel, data)) }
            catch (failure: IllegalAccessException) {
                throw IllegalStateException("Cannot call TabooLib OpenAPI in $name", failure)
            } catch (failure: InvocationTargetException) {
                val cause = failure.cause
                if (cause is RuntimeException) throw cause
                throw IllegalStateException("TabooLib OpenAPI failed in $name", cause ?: failure)
            }
        }
    }

    companion object {
        private const val MAIN_SUFFIX = ".platform.BukkitPlugin"
        private const val API_SUFFIX = ".common.OpenAPI"
        @JvmStatic fun bukkit(providerName: String): TabooLibKetherInterop.ContainerDiscovery = BukkitDiscovery(providerName)
        @JvmStatic fun apiName(mainClass: String?): String {
            require(mainClass != null && mainClass.endsWith(MAIN_SUFFIX)) { "TabooLib main class must end with $MAIN_SUFFIX" }
            return mainClass.dropLast(MAIN_SUFFIX.length) + API_SUFFIX
        }
        @JvmStatic fun castResult(source: Any?): TabooLibKetherInterop.OpenResult = when (source) {
            null -> TabooLibKetherInterop.OpenResult.failed()
            is TabooLibKetherInterop.OpenResult -> source
            else -> {
                val successful = property(source, "successful")
                val value = property(source, "value")
                if (successful == true) TabooLibKetherInterop.OpenResult.successful(value) else TabooLibKetherInterop.OpenResult.failed()
            }
        }
        @JvmStatic fun property(source: Any, name: String): Any? {
            val suffix = name[0].uppercaseChar() + name.substring(1)
            try { return invoke(source, "get$suffix") } catch (_: IllegalStateException) {
                try { return invoke(source, "is$suffix") } catch (unsupported: IllegalStateException) {
                    val field = field(source.javaClass, name) ?: throw unsupported
                    return try { field.isAccessible = true; field.get(source) }
                    catch (failure: IllegalAccessException) { throw IllegalStateException(failure) }
                }
            }
        }
        @JvmStatic fun setProperty(source: Any, name: String, value: Any?) {
            try { invoke(source, "set" + name[0].uppercaseChar() + name.substring(1), value) }
            catch (unsupported: IllegalStateException) {
                val field = field(source.javaClass, name) ?: throw unsupported
                try { field.isAccessible = true; field.set(source, value) }
                catch (failure: IllegalAccessException) { throw IllegalStateException(failure) }
            }
        }
        private fun field(type: Class<*>, name: String): Field? {
            var current: Class<*>? = type
            while (current != null) {
                try { return current.getDeclaredField(name) }
                catch (_: NoSuchFieldException) { current = current.superclass }
            }
            return null
        }
        @JvmStatic fun invokeStatic(type: String, name: String, vararg arguments: Any?): Any? = try {
            invoke(Class.forName(type), null, name, arguments)
        } catch (failure: ClassNotFoundException) { throw IllegalStateException("$type is not available", failure) }
        @JvmStatic fun invoke(target: Any, name: String, vararg arguments: Any?): Any? = invoke(target.javaClass, target, name, arguments)
        private fun invoke(type: Class<*>, target: Any?, name: String, arguments: Array<out Any?>): Any? {
            val method = type.methods.firstOrNull { it.name == name && compatible(it.parameterTypes, arguments) }
                ?: throw IllegalStateException(NoSuchMethodException(type.name + '.' + name))
            try { method.isAccessible = true; return method.invoke(target, *arguments) }
            catch (failure: IllegalAccessException) { throw IllegalStateException(failure) }
            catch (failure: InvocationTargetException) {
                val cause = failure.cause
                if (cause is RuntimeException) throw cause
                throw IllegalStateException(cause ?: failure)
            }
        }
        private fun compatible(parameters: Array<Class<*>>, arguments: Array<out Any?>): Boolean =
            parameters.size == arguments.size && parameters.indices.all { arguments[it] == null || boxed(parameters[it]).isInstance(arguments[it]) }
        private fun boxed(type: Class<*>): Class<*> = when (type) {
            Boolean::class.javaPrimitiveType -> Boolean::class.javaObjectType
            Byte::class.javaPrimitiveType -> Byte::class.javaObjectType
            Short::class.javaPrimitiveType -> Short::class.javaObjectType
            Int::class.javaPrimitiveType -> Int::class.javaObjectType
            Long::class.javaPrimitiveType -> Long::class.javaObjectType
            Float::class.javaPrimitiveType -> Float::class.javaObjectType
            Double::class.javaPrimitiveType -> Double::class.javaObjectType
            Char::class.javaPrimitiveType -> Char::class.javaObjectType
            else -> type
        }
    }
}
