package me.kzheart.klib.core.kotlin

/** Marks receiver-based Klib builders. */
@DslMarker
@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE)
@Retention(AnnotationRetention.BINARY)
public annotation class KlibDsl
