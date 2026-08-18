package me.kzheart.klib.data.cache;

/** 为面向字节的键值契约编解码缓存值。 */
public interface DataCodec<T> {
    byte[] encode(T value);

    T decode(byte[] value);
}
