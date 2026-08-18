package me.kzheart.klib.ui.prompt;

import java.util.Optional;

/** 解析一行聊天输入；返回空表示提示应继续等待。 */
@FunctionalInterface
public interface PromptParser<T> {
    Optional<T> parse(String message);
}
