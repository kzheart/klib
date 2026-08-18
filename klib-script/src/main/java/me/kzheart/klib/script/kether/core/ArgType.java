/* Copyright (c) 2018 Bkm016. MIT License. Upstream: c27e822fb34eebd7433a94efbfac0a26943cccd6 */
package me.kzheart.klib.script.kether.core;

/** 从任务读取器中读取一个有类型参数。 */
@FunctionalInterface
public interface ArgType<T> {

    T read(QuestReader reader) throws LocalizedException;
}
