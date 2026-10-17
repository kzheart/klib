package me.kzheart.klib.config.api

import me.kzheart.klib.config.annotation.ConfigFile
import me.kzheart.klib.scope.Scope

class Configs(private val owner: Scope) {
    fun <T> load(type: Class<T>): ConfigDocument<T> {
        val file = type.getAnnotation(ConfigFile::class.java)
            ?: throw IllegalArgumentException("${type.name}: missing @ConfigFile")
        return load(type, file.value)
    }
    fun <T> load(type: Class<T>, path: String): ConfigDocument<T> = owner.config(type, path)
}
