package me.kzheart.klib.lang;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

final class FallbackMessageCatalog implements MessageCatalog {
    private final MessageCatalog primary;
    private final MessageCatalog fallback;

    FallbackMessageCatalog(MessageCatalog primary, MessageCatalog fallback) {
        this.primary = Objects.requireNonNull(primary, "primary");
        this.fallback = Objects.requireNonNull(fallback, "fallback");
    }

    @Override
    public Optional<String> find(String key) {
        Optional<String> value = primary.find(key);
        return value.isPresent() ? value : fallback.find(key);
    }

    @Override
    public Optional<List<String>> findLines(String key) {
        Optional<List<String>> value = primary.findLines(key);
        return value.isPresent() ? value : fallback.findLines(key);
    }

    @Override
    public Optional<RichText> findRich(String key) {
        Optional<RichText> value = primary.findRich(key);
        return value.isPresent() ? value : fallback.findRich(key);
    }

    /** 源优先级：拥有该键的消息目录决定其渲染方式。 */
    @Override
    public Optional<Object> findAny(String key) {
        Optional<Object> value = primary.findAny(key);
        return value.isPresent() ? value : fallback.findAny(key);
    }
}
