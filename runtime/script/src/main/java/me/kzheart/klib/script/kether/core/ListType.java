/* Copyright (c) 2018 Bkm016. MIT License. Upstream: c27e822fb34eebd7433a94efbfac0a26943cccd6 */
package me.kzheart.klib.script.kether.core;

import java.util.List;

/** 保留不变的 List<T> 泛型签名，以及桥接方法的 Exceptions 属性。 */
public final class ListType<T> implements ArgType<List<T>> {
    private final ArgType<T> elementType;
    ListType(ArgType<T> elementType) { this.elementType = elementType; }
    @Override public List<T> read(QuestReader reader) throws LocalizedException {
        return ArgumentReaders.list(reader, elementType);
    }
}
