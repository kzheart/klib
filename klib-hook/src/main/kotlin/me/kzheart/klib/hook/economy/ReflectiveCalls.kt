package me.kzheart.klib.hook.economy

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.Arrays
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/** 缓存反射查找，包括缺失方法；适配器不直接链接任何经济插件 API。 */
internal class ReflectiveCalls private constructor() {
    companion object {
        private val METHODS = ConcurrentHashMap<MethodKey, Optional<Method>>()

        @JvmStatic fun invoke(target: Any, name: String, vararg arguments: Any?): Any? {
            val method = find(target.javaClass, name, arguments)
                ?: throw IllegalStateException("Missing method ${target.javaClass.name}.$name")
            return try {
                method.invoke(target, *arguments)
            } catch (failure: IllegalAccessException) {
                throw IllegalStateException("Cannot access $method", failure)
            } catch (failure: InvocationTargetException) {
                val cause = failure.cause
                if (cause is RuntimeException) throw cause
                throw IllegalStateException("Integration call failed: $method", cause)
            }
        }

        @JvmStatic fun invokeEither(target: Any, first: String, second: String, vararg arguments: Any?): Any? =
            invoke(target, if (find(target.javaClass, first, arguments) == null) second else first, *arguments)

        @JvmStatic fun hasMethod(target: Any, name: String, vararg arguments: Any?): Boolean =
            find(target.javaClass, name, arguments) != null

        @JvmStatic fun succeeded(result: Any?): Boolean {
            if (result == null) return true
            if (result is Boolean) return result
            if (hasMethod(result, "transactionSuccess")) return invoke(result, "transactionSuccess") == true
            return try {
                result.javaClass.getField("transactionSuccess").getBoolean(result)
            } catch (failure: NoSuchFieldException) {
                throw IllegalStateException("Unknown transaction result: ${result.javaClass.name}", failure)
            } catch (failure: IllegalAccessException) {
                throw IllegalStateException("Cannot read transaction result", failure)
            }
        }

        private fun find(type: Class<*>, name: String, arguments: Array<out Any?>): Method? {
            val key = MethodKey(type, name, Array(arguments.size) { arguments[it]?.javaClass })
            var cached = METHODS[key]
            if (cached == null) {
                cached = Optional.ofNullable(scan(type, name, arguments))
                METHODS.putIfAbsent(key, cached)
            }
            return cached.orElse(null)
        }

        private fun scan(type: Class<*>, name: String, arguments: Array<out Any?>): Method? =
            type.methods.firstOrNull { method ->
                method.name == name && method.parameterTypes.size == arguments.size &&
                    compatible(method.parameterTypes, arguments)
            }

        private fun compatible(parameters: Array<Class<*>>, arguments: Array<out Any?>): Boolean =
            parameters.indices.all { index ->
                val argument = arguments[index]
                if (argument == null) !parameters[index].isPrimitive
                else boxed(parameters[index]).isInstance(argument)
            }

        private fun boxed(type: Class<*>): Class<*> = when (type) {
            Boolean::class.javaPrimitiveType -> Boolean::class.javaObjectType
            Int::class.javaPrimitiveType -> Int::class.javaObjectType
            Long::class.javaPrimitiveType -> Long::class.javaObjectType
            Double::class.javaPrimitiveType -> Double::class.javaObjectType
            Float::class.javaPrimitiveType -> Float::class.javaObjectType
            Short::class.javaPrimitiveType -> Short::class.javaObjectType
            Byte::class.javaPrimitiveType -> Byte::class.javaObjectType
            Char::class.javaPrimitiveType -> Char::class.javaObjectType
            else -> type
        }
    }

    private class MethodKey(val type: Class<*>, val name: String, val argumentTypes: Array<Class<*>?>) {
        override fun equals(other: Any?): Boolean = this === other ||
            (other is MethodKey && type == other.type && name == other.name && Arrays.equals(argumentTypes, other.argumentTypes))
        override fun hashCode(): Int = 31 * (31 * type.hashCode() + name.hashCode()) + Arrays.hashCode(argumentTypes)
    }
}
