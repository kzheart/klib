package me.kzheart.klib.data;

import java.util.Map;
import java.util.Optional;

/** 带命名空间、面向字节的键值视图。 */
public interface KeyValueStore {
    Optional<byte[]> get(String namespace, String key);

    void put(String namespace, String key, byte[] value);

    void delete(String namespace, String key);

    Map<String, byte[]> entries(String namespace);
}
