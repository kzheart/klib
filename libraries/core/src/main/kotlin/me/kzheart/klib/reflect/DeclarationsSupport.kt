package me.kzheart.klib.reflect

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Shared deterministic declaration validation, derived from the relocated package. */
internal class DeclarationsSupport private constructor() {
    companion object {
        @JvmStatic fun methods(type: Class<*>): List<Method> {
            val prefix = Declarations::class.java.`package`.name.substringBeforeLast('.') + "."
            generateSequence(type) { it.superclass }.takeWhile { it != Any::class.java }.forEach { parent ->
                for (method in parent.declaredMethods) {
                    val annotated = method.annotations.any {
                        val name = it.annotationClass.java.name
                        name.startsWith(prefix) || name == "org.bukkit.event.EventHandler"
                    }
                    if (annotated && (!Modifier.isPublic(method.modifiers) || Modifier.isStatic(method.modifiers))) {
                        throw invalid(method, "annotated method must be public and non-static")
                    }
                }
            }
            return type.methods.filterNot { it.isBridge || it.isSynthetic }.sortedBy { it.toGenericString() }
        }
        @JvmStatic fun voidMethod(method: Method, parameters: Int) {
            if (method.returnType != Void.TYPE || method.parameterCount != parameters || Modifier.isStatic(method.modifiers)) {
                throw invalid(method, "expected void instance method with $parameters parameters")
            }
        }
        @JvmStatic fun invalid(method: Method, reason: String): IllegalArgumentException =
            IllegalArgumentException("${method.toGenericString()}: $reason")
        @JvmStatic fun invoke(target: Any, method: Method, vararg args: Any?): Any? = try {
            method.isAccessible = true
            method.invoke(target, *args)
        } catch (failure: InvocationTargetException) {
            when (val cause = failure.cause) {
                is RuntimeException -> throw cause
                is Error -> throw cause
                else -> throw IllegalStateException(method.toGenericString(), cause)
            }
        } catch (failure: ReflectiveOperationException) {
            throw IllegalStateException(method.toGenericString(), failure)
        }
    }
}
