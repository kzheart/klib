package me.kzheart.klib.lang;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

final class ReloadableMessageCatalog implements MessageCatalog {
    private volatile MessageCatalog delegate;

    ReloadableMessageCatalog(MessageCatalog delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    void replace(MessageCatalog replacement) {
        delegate = Objects.requireNonNull(replacement, "replacement");
    }

    @Override
    public Optional<String> find(String key) {
        return delegate.find(key);
    }

    @Override
    public Optional<List<String>> findLines(String key) {
        return delegate.findLines(key);
    }

    @Override
    public Optional<RichText> findRich(String key) {
        return delegate.findRich(key);
    }

    @Override
    public Optional<Object> findAny(String key) {
        // 只读取一次 volatile 字段，避免并发替换导致一次查找使用不同快照。
        MessageCatalog snapshot = delegate;
        return snapshot.findAny(key);
    }
}
