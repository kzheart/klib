/* Copyright (c) 2018 Bkm016. MIT License. Upstream: c27e822fb34eebd7433a94efbfac0a26943cccd6 */
package me.kzheart.klib.script.kether.core;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** 已解析动作及其加载器元数据。 */
public final class ParsedAction<A> {

    private final QuestAction<A> action;
    private final Map<String, Object> properties;

    public ParsedAction(QuestAction<A> action) {
        this(action, new HashMap<>());
    }

    public ParsedAction(QuestAction<A> action, Map<String, Object> properties) {
        this.action = Objects.requireNonNull(action, "action");
        this.properties = new HashMap<>(properties);
    }

    public CompletableFuture<A> process(QuestContext.Frame frame) {
        return action.process(frame);
    }

    @SuppressWarnings("unchecked")
    public <T> T get(ActionProperty<T> key) {
        return Objects.requireNonNull((T) properties.get(key.id), key.id);
    }

    @SuppressWarnings("unchecked")
    public <T> T get(ActionProperty<T> key, T defaultValue) {
        T value = (T) properties.get(key.id);
        return value == null ? defaultValue : value;
    }

    public <T> void set(ActionProperty<T> key, T value) {
        properties.put(key.id, Objects.requireNonNull(value, "value"));
    }

    public <T> boolean has(ActionProperty<T> key) {
        return properties.containsKey(key.id);
    }

    public QuestAction<?> getAction() {
        return action;
    }

    public Map<String, Object> getProperties() {
        return properties;
    }

    @Override
    public String toString() {
        return "Parsed[" + action + ", " + properties + ']';
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ParsedAction)) return false;
        ParsedAction<?> that = (ParsedAction<?>) other;
        return Objects.equals(action, that.action) && Objects.equals(properties, that.properties);
    }

    @Override
    public int hashCode() {
        return Objects.hash(action, properties);
    }

    public static <T> ParsedAction<T> noop() {
        return new ParsedAction<>(QuestAction.noop());
    }

    /** 动作元数据的类型安全键。 */
    public static final class ActionProperty<T> {
        private final String id;

        private ActionProperty(String id) {
            this.id = Objects.requireNonNull(id, "id");
        }

        @Override
        public boolean equals(Object other) {
            return this == other || other instanceof ActionProperty && id.equals(((ActionProperty<?>) other).id);
        }

        @Override
        public int hashCode() { return id.hashCode(); }

        @Override
        public String toString() { return "ActionProperty{id='" + id + "'}"; }

        public static <T> ActionProperty<T> of(String id) {
            return new ActionProperty<>(id);
        }
    }
}
