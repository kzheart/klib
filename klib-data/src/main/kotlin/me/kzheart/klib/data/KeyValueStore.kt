package me.kzheart.klib.data

import java.util.Optional

/** 带命名空间、面向字节的键值视图。 */
@JvmSuppressWildcards
interface KeyValueStore {
    fun get(namespace: String, key: String): Optional<ByteArray>
    fun put(namespace: String, key: String, value: ByteArray)
    fun delete(namespace: String, key: String)
    fun entries(namespace: String): Map<String, ByteArray>
}
