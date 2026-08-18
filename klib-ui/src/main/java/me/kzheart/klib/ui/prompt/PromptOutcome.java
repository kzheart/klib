package me.kzheart.klib.ui.prompt;

import java.util.Optional;

/** 不可变的聊天提示结果。 */
public final class PromptOutcome<T> {
    private final PromptStatus status;
    private final T value;

    PromptOutcome(PromptStatus status, T value) {
        this.status = status;
        this.value = value;
    }

    public PromptStatus status() {
        return status;
    }

    public Optional<T> value() {
        return Optional.ofNullable(value);
    }
}
