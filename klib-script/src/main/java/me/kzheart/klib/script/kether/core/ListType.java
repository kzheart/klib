/* Copyright (c) 2018 Bkm016. MIT License. Upstream: c27e822fb34eebd7433a94efbfac0a26943cccd6 */
package me.kzheart.klib.script.kether.core;

import java.util.ArrayList;
import java.util.List;

/** 读取以方括号分隔的参数列表。 */
public final class ListType<T> implements ArgType<List<T>> {

    private final ArgType<T> elementType;

    ListType(ArgType<T> elementType) {
        this.elementType = elementType;
    }

    @Override
    public List<T> read(QuestReader reader) throws LocalizedException {
        reader.expect("[");
        ArrayList<T> list = new ArrayList<>();
        while (reader.hasNext() && reader.peek() != ']') {
            list.add(reader.next(elementType));
        }
        list.trimToSize();
        reader.expect("]");
        return list;
    }
}
