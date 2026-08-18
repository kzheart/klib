package me.kzheart.klib.lang;

import java.util.Optional;
import java.util.List;

/** 解析本地化消息键。 */
public interface MessageCatalog {
    Optional<String> find(String key);

    default Optional<List<String>> findLines(String key) {
        return Optional.empty();
    }

    default Optional<RichText> findRich(String key) {
        return Optional.empty();
    }

    /**
     * 单次查找，将条目作为 {@link String}、{@code List<String>} 或 {@link RichText} 返回。
     * 由可变状态支持的实现应基于同一份快照完成解析，避免并发重新加载导致结果不一致。
     */
    default Optional<Object> findAny(String key) {
        Optional<RichText> rich = findRich(key);
        if (rich.isPresent()) {
            return Optional.<Object>of(rich.get());
        }
        Optional<List<String>> lines = findLines(key);
        if (lines.isPresent()) {
            return Optional.<Object>of(lines.get());
        }
        Optional<String> value = find(key);
        return value.isPresent() ? Optional.<Object>of(value.get()) : Optional.empty();
    }
}
