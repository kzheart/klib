package me.kzheart.klib.command

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Uses public API declarations only; modern Paper classes never enter Java 8 signatures. */
internal class PublicCommandReflection private constructor() {
    companion object {
        @JvmStatic @Throws(ClassNotFoundException::class) fun type(name: String): Class<*> =
            Class.forName(name, false, PublicCommandReflection::class.java.classLoader)
        @JvmStatic fun invoke(target: Any, name: String, vararg args: Any?): Any? =
            call(find(target.javaClass, name, false, args) ?: throw IllegalStateException("Missing public API: ${target.javaClass.name}.$name"), target, args)
        @JvmStatic fun invokeStatic(owner: String, name: String, vararg args: Any?): Any? = try {
            call(find(type(owner), name, true, args) ?: throw IllegalStateException("Missing public API: $owner.$name"), null, args)
        } catch (failure: ClassNotFoundException) { throw IllegalStateException("Missing public API: $owner", failure) }
        private fun find(type: Class<*>, name: String, isStatic: Boolean, args: Array<out Any?>): Method? {
            // Prefer public interfaces when the runtime implementation itself is not public.
            for (api in type.interfaces) find(api, name, isStatic, args)?.let { return it }
            if (Modifier.isPublic(type.modifiers)) type.methods.firstOrNull { method ->
                Modifier.isPublic(method.declaringClass.modifiers) && method.name == name &&
                    Modifier.isStatic(method.modifiers) == isStatic && method.parameterCount == args.size &&
                    args.indices.all { args[it] == null || method.parameterTypes[it].isInstance(args[it]) }
            }?.let { return it }
            return type.superclass?.let { find(it, name, isStatic, args) }
        }
        private fun call(method: Method, target: Any?, args: Array<out Any?>): Any? = try { method.invoke(target, *args) }
        catch (failure: IllegalAccessException) { throw IllegalStateException("Cannot access public API: $method", failure) }
        catch (failure: InvocationTargetException) {
            when (val cause = failure.cause) {
                is RuntimeException -> throw cause
                is Error -> throw cause
                else -> throw IllegalStateException("Public API failed: $method", cause)
            }
        }
    }
}
