/* Kether property semantics: Copyright (c) 2018 Bkm016, MIT. See META-INF/LICENSE-TabooLib-Kether.txt. */
package me.kzheart.klib.script

import me.kzheart.klib.script.kether.core.QuestContext
import java.lang.reflect.Array as ReflectArray
import java.util.Locale
import java.util.logging.Level
import java.util.regex.Matcher

/** 宿主属性访问优先，内建类型回退集中在此处。 */
class ScriptProperties private constructor() {
    companion object {
        @JvmStatic fun read(frame: QuestContext.Frame, instance: Any?, key: String): Any? {
            if (instance == null) { warn(frame, "Property object must be not null."); return null }
            val host = CoreScriptRuntime.context(frame).service(ScriptPropertyAccess::class.java).orElse(null)
            val external = host?.read(instance, key)
            if (external != null && external.isSupported) return external.value()
            val builtin = readBuiltin(instance, key)
            if (builtin.isSupported) return builtin.value()
            warn(frame, "${instance.javaClass.simpleName}[$key] not supported yet.")
            return null
        }
        @JvmStatic fun write(frame: QuestContext.Frame, instance: Any?, key: String, value: Any?): Boolean {
            val external = CoreScriptRuntime.context(frame).service(ScriptPropertyAccess::class.java).orElse(null)?.write(instance, key, value)
            return external?.isSupported == true || writeBuiltin(instance, key, value)
        }
        @JvmStatic fun readBuiltin(instance: Any, key: String): ScriptPropertyAccess.Result = when (instance) {
            is String -> when (key) {
                "upper", "uppercase" -> supported(instance.uppercase(Locale.ROOT))
                "lower", "lowercase" -> supported(instance.lowercase(Locale.ROOT))
                "length", "size" -> supported(instance.length)
                "trim" -> supported(instance.trim { it <= ' ' })
                else -> ScriptPropertyAccess.Result.unsupported()
            }
            is Map<*, *> -> if (key.startsWith("@")) supported(instance[key.substring(1)]) else when (key) {
                "length", "size" -> supported(instance.size)
                "keys" -> supported(instance.keys)
                "values" -> supported(instance.values)
                else -> ScriptPropertyAccess.Result.unsupported()
            }
            is List<*> -> when {
                KetherSupport.isInt(key) -> supported(instance[key.toInt()])
                key == "length" || key == "size" -> supported(instance.size)
                else -> ScriptPropertyAccess.Result.unsupported()
            }
            is Matcher -> try { supported(if (KetherSupport.isInt(key)) instance.group(key.toInt()) else instance.group(key)) }
                catch (_: RuntimeException) { ScriptPropertyAccess.Result.unsupported() }
            else -> when {
                !instance.javaClass.isArray -> ScriptPropertyAccess.Result.unsupported()
                KetherSupport.isInt(key) -> supported(ReflectArray.get(instance, key.toInt()))
                key == "length" || key == "size" -> supported(ReflectArray.getLength(instance))
                else -> ScriptPropertyAccess.Result.unsupported()
            }
        }
        @Suppress("UNCHECKED_CAST")
        private fun writeBuiltin(instance: Any?, key: String, value: Any?): Boolean {
            if (instance is Map<*, *> && key.startsWith("@")) {
                val map = instance as MutableMap<Any?, Any?>
                if (value == null) map.remove(key.substring(1)) else map[key.substring(1)] = value
                return true
            }
            if (instance is List<*> && KetherSupport.isInt(key)) { (instance as MutableList<Any?>)[key.toInt()] = value; return true }
            if (instance != null && instance.javaClass.isArray && KetherSupport.isInt(key)) { ReflectArray.set(instance, key.toInt(), value); return true }
            return false
        }
        private fun supported(value: Any?): ScriptPropertyAccess.Result = ScriptPropertyAccess.Result.supported(value)
        private fun warn(frame: QuestContext.Frame, message: String) {
            CoreScriptRuntime.context(frame).service(ScriptLogger::class.java).orElse(null)?.log(Level.WARNING, message)
        }
    }
}
