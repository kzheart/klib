package me.kzheart.klib.lang;

import java.util.Objects;

/** 由库定义的悬停或点击表达式。 */
public final class TextAction {
    public enum Type {
        HOVER_TEXT,
        HOVER_ITEM,
        HOVER_ENTITY,
        RUN_COMMAND,
        SUGGEST_COMMAND,
        OPEN_URL,
        OPEN_FILE,
        CHANGE_PAGE,
        COPY_TO_CLIPBOARD
    }

    private final Type type;
    private final String value;

    public TextAction(Type type, String value) {
        this.type = Objects.requireNonNull(type, "type");
        this.value = Objects.requireNonNull(value, "value");
    }

    public Type type() {
        return type;
    }

    public String value() {
        return value;
    }
}
