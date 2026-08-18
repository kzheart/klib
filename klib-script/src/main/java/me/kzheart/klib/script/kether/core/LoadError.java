/* Copyright (c) 2018 Bkm016. MIT License. Upstream: c27e822fb34eebd7433a94efbfac0a26943cccd6 */
package me.kzheart.klib.script.kether.core;

import java.util.Locale;

/** Kether 加载失败的稳定分类。 */
public enum LoadError {
    STRING_NOT_CLOSE,
    NOT_MATCH,
    UNKNOWN_ACTION,
    NOT_DURATION,
    EOF,
    BLOCK_ERROR,
    UNHANDLED;

    public LocalizedException create(Object... arguments) {
        return LocalizedException.of(
                this, "load-error." + name().toLowerCase(Locale.ROOT).replace('_', '-'), arguments);
    }
}
