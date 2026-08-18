package me.kzheart.klib.config;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import me.kzheart.klib.scope.Disposable;

/** 用于测试和程序化配置的可变重新加载源。 */
public final class InMemoryConfigSource implements ConfigSource {
    private final String sourceName;
    private final ListenerList watchers = new ListenerList();
    private final AtomicLong generation = new AtomicLong();
    private volatile String content;

    public InMemoryConfigSource(String sourceName, String content) {
        this.sourceName = Objects.requireNonNull(sourceName, "sourceName");
        this.content = Objects.requireNonNull(content, "content");
    }

    @Override
    public String sourceName() {
        return sourceName;
    }

    @Override
    public PreparedConfig prepare() {
        final long preparedGeneration = generation.get();
        final String preparedContent = content;
        final YamlDocument document = YamlDocument.parse(sourceName, preparedContent);
        return new PreparedConfig() {
            @Override
            public YamlDocument document() {
                return document;
            }

            @Override
            public void commit() {
                if (generation.get() != preparedGeneration) {
                    throw new ConfigException(
                            sourceName + ":<root>: source changed while configuration was loading");
                }
            }

            @Override
            public String revision() {
                return preparedContent;
            }
        };
    }

    @Override
    public Disposable watch(Runnable listener) {
        Objects.requireNonNull(listener, "listener");
        watchers.add(listener);
        return watchers.registration(listener);
    }

    public void update(String newContent) {
        content = Objects.requireNonNull(newContent, "newContent");
        generation.incrementAndGet();
    }

    public void signalChange() {
        for (Runnable watcher : watchers.snapshot()) {
            watcher.run();
        }
    }
}
