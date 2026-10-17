package me.kzheart.klib.data.cache

/** 为面向字节的键值契约编解码缓存值。 */
interface DataCodec<T> {
    fun encode(value: T): ByteArray
    fun decode(value: ByteArray): T
}
