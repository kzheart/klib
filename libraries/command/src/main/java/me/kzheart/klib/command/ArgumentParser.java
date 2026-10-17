package me.kzheart.klib.command;

/**
 * 自定义参数解析扩展点，配合 {@link Arguments#custom} 使用。
 *
 * <p>返回 {@code null} 或抛出 {@link IllegalArgumentException} 均视为解析失败。</p>
 */
public interface ArgumentParser<T> {
    T parse(String input);
}
