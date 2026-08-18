/* Copyright (c) 2018 Bkm016. MIT License. Upstream: c27e822fb34eebd7433a94efbfac0a26943cccd6 */
package me.kzheart.klib.script.kether.core;

import java.util.Optional;
import java.util.function.Function;

/** 将词元转换为请求的 Java 类型。 */
public final class CoerceType<T> implements ArgType<T> {

    private final Function<Object, Optional<T>> function;
    private final String type;

    CoerceType(Function<Object, Optional<T>> function, String type) {
        this.function = function;
        this.type = type;
    }

    @Override
    public T read(QuestReader reader) throws LocalizedException {
        String token = reader.nextToken();
        return function.apply(token).orElseThrow(LocalizedException.supply("not_" + type, token));
    }
}
