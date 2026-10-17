/* Copyright (c) 2018 Bkm016. MIT License. Upstream: c27e822fb34eebd7433a94efbfac0a26943cccd6 */
package me.kzheart.klib.script.kether.core;

import java.util.Arrays;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** 文本可由活动服务本地化的结构化 Kether 错误。 */
public class LocalizedException extends RuntimeException {

    private static final long serialVersionUID = 1L;
    private final LoadError error;
    private final String node;
    private final transient Object[] params;

    public LocalizedException(LoadError error, String node, Object[] params) {
        super(node);
        this.error = error;
        this.node = node;
        this.params = params.clone();
    }

    public LoadError getError() {
        return error;
    }

    public String getNode() {
        return node;
    }

    public Object[] getParams() {
        return params.clone();
    }

    @Override
    public String getLocalizedMessage() {
        QuestService<?> service = ServiceHolder.getQuestServiceInstance();
        return service == null ? node + Arrays.toString(params) : service.getLocalizedText(node, params);
    }

    public Stream<LocalizedException> stream() {
        return Stream.of(this);
    }

    public LocalizedException then(LocalizedException exception) {
        return batch(this, exception);
    }

    public static LocalizedException of(LoadError error, String node, Object... params) {
        return new LocalizedException(error, node, params);
    }

    public static LocalizedException of(String node, Object... params) {
        return new LocalizedException(LoadError.UNKNOWN_ACTION, node, params);
    }

    public static Supplier<LocalizedException> supply(String node, Object... params) {
        return () -> of(node, params);
    }

    public static LocalizedException batch(LocalizedException... exceptions) {
        return new Concat(exceptions);
    }

    private static final class Concat extends LocalizedException {

        private static final long serialVersionUID = 1L;
        private final LocalizedException[] exceptions;

        private Concat(LocalizedException... exceptions) {
            super(LoadError.UNKNOWN_ACTION, exceptions[0].node, exceptions[0].params);
            this.exceptions = exceptions.clone();
        }

        @Override
        public Stream<LocalizedException> stream() {
            return Arrays.stream(exceptions);
        }

        @Override
        public String getLocalizedMessage() {
            return stream().map(LocalizedException::getLocalizedMessage).collect(Collectors.joining("\n"));
        }

        @Override
        public LocalizedException then(LocalizedException exception) {
            LocalizedException[] combined = Arrays.copyOf(exceptions, exceptions.length + 1);
            combined[exceptions.length] = exception;
            return new Concat(combined);
        }
    }
}
