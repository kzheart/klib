@file:JvmName("KotlinConfigs")

package me.kzheart.klib.config

import me.kzheart.klib.config.api.ConfigDocument
import me.kzheart.klib.config.api.Configs
import kotlin.reflect.KClass

/** Load an annotated config model through the scope-owned config capability. */
inline fun <reified T : Any> Configs.load(): ConfigDocument<T> = load(T::class.java)

/** Load a config model from a relative resource path. */
inline fun <reified T : Any> Configs.load(path: String): ConfigDocument<T> = load(T::class.java, path)

fun <T : Any> Configs.load(type: KClass<T>): ConfigDocument<T> = load(type.java)

fun <T : Any> Configs.load(type: KClass<T>, path: String): ConfigDocument<T> = load(type.java, path)

/** Map a node, preserving the mapper's converters and source diagnostics. */
inline fun <reified T : Any> YamlConfigMapper.read(node: ConfigNode): T = read(node, T::class)

fun <T : Any> YamlConfigMapper.read(node: ConfigNode, type: KClass<T>): T =
    read(node, type.java) ?: throw node.mappingError("null cannot be assigned to non-null Kotlin type ${type.qualifiedName}")

/** Generate defaults explicitly; all Kotlin constructor parameters must have defaults. */
inline fun <reified T : Any> configDefaults(): String = ConfigDefaults.yaml(T::class.java)

fun configDefaults(type: KClass<*>): String = ConfigDefaults.yaml(type.java)
