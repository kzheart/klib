package me.kzheart.klib.script.taboolib.common

/** 跨加载器协议结果的不可变 Kotlin 值。Java 声明层保留可空平台 getter。 */
internal class OpenResultValue(val successful: Boolean, val value: Any?)
