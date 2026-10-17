/* Copyright (c) 2018 Bkm016. MIT License. Upstream: c27e822fb34eebd7433a94efbfac0a26943cccd6 */
package me.kzheart.klib.script.kether.core

import java.util.concurrent.CompletableFuture

/** 读取器的基础动作：字面量、变量和宿主属性。 */
internal class CoreActions private constructor() {
    class Literal<T> @JvmOverloads constructor(private val value: T, private val misspelled: Boolean = false) : QuestAction<T>() {
        override fun process(frame: QuestContext.Frame): CompletableFuture<T> = CompletableFuture.completedFuture(value)
        fun getValue(): T = value
        fun isMisspelled(): Boolean = misspelled
        override fun toString(): String = "Literal{$value}"
    }

    class Get<T>(private val name: String) : QuestAction<T>() {
        override fun process(frame: QuestContext.Frame): CompletableFuture<T> =
            CompletableFuture.completedFuture(frame.variables().getOrNull(name))
        override fun toString(): String = "Get{$name}"
    }

    class PropertyGet(private val instance: ParsedAction<*>, private val key: String) : QuestAction<Any?>() {
        override fun process(frame: QuestContext.Frame): CompletableFuture<Any?> {
            val accessor = frame.context().service.propertyAccessor
                ?: throw IllegalStateException("Property access is not installed: $key")
            val value = frame.newFrame(instance).run<Any?>()
            return if (value.isDone) value.thenApply { accessor.read(frame, it, key) }
                else value.thenApplyAsync({ accessor.read(frame, it, key) }, frame.context().executor)
        }
        fun getInstance(): ParsedAction<*> = instance
        fun getKey(): String = key
        override fun toString(): String = "PropertyGet{$instance[$key]}"
    }
}
