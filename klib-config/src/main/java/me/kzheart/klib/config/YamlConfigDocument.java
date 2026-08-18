package me.kzheart.klib.config;

import java.util.Objects;
import java.util.Optional;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.logging.Level;
import java.util.logging.Logger;
import me.kzheart.klib.config.api.ConfigDocument;
import me.kzheart.klib.diagnostic.DiagnosticSource;
import me.kzheart.klib.scope.Disposable;
import me.kzheart.klib.scope.Scope;

/** 可重新加载 YAML 源的原子类型化视图。 */
public final class YamlConfigDocument<T>
        implements ConfigDocument<T>, ReloadFailureSource, DiagnosticSource {
    private static final Logger LOGGER = Logger.getLogger(YamlConfigDocument.class.getName());
    private final Object lifecycleLock = new Object();
    private final Object reloadMutex = new Object();
    private final Scope owner;
    private final ConfigSource source;
    private final YamlConfigMapper mapper;
    private final Class<T> type;
    private final Consumer<Runnable> listenerExecutor;
    private final ListenerList listeners = new ListenerList();
    private final FailureListenerList failureListeners = new FailureListenerList();

    private volatile T value;
    private volatile String revision;
    private volatile Throwable lastFailure;
    private volatile Throwable lastListenerFailure;
    private Disposable watcher;
    private boolean disposed;

    private YamlConfigDocument(
            Scope owner,
            ConfigSource source,
            YamlConfigMapper mapper,
            Class<T> type,
            Consumer<Runnable> listenerExecutor,
            T initialValue,
            String initialRevision
    ) {
        this.owner = owner;
        this.source = source;
        this.mapper = mapper;
        this.type = type;
        this.listenerExecutor = listenerExecutor;
        this.value = initialValue;
        this.revision = initialRevision;
    }

    static <T> YamlConfigDocument<T> open(
            Scope owner,
            ConfigSource source,
            YamlConfigMapper mapper,
            Class<T> type
    ) {
        return open(owner, source, mapper, type, Runnable::run);
    }

    static <T> YamlConfigDocument<T> open(
            Scope owner,
            ConfigSource source,
            YamlConfigMapper mapper,
            Class<T> type,
            Consumer<Runnable> listenerExecutor
    ) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(listenerExecutor, "listenerExecutor");
        if (owner.isClosed()) {
            throw new IllegalStateException("Cannot load config into closed scope: " + owner.name());
        }

        MappedConfig<T> initial = map(source, mapper, type);
        YamlConfigDocument<T> document = new YamlConfigDocument<T>(
                owner,
                source,
                mapper,
                type,
                listenerExecutor,
                initial.value,
                initial.revision);
        try {
            owner.install(document);
            Disposable watcher = source.watch(document::reloadFromWatcher);
            document.watcher = watcher;
            try {
                owner.install(watcher);
            } catch (RuntimeException failure) {
                watcher.dispose();
                throw failure;
            }
            return document;
        } catch (RuntimeException failure) {
            document.dispose();
            throw failure;
        }
    }

    @Override
    public String sourceName() {
        return source.sourceName();
    }

    @Override
    public T value() {
        return value;
    }

    @Override
    public void reload() {
        reload(true);
    }

    @Override
    public CompletionStage<Void> reloadAsync() {
        try {
            return reload(true);
        } catch (RuntimeException failure) {
            CompletableFuture<Void> failed = new CompletableFuture<Void>();
            failed.completeExceptionally(failure);
            return failed;
        }
    }

    private CompletionStage<Void> reload(boolean notifyWhenUnchanged) {
        synchronized (reloadMutex) {
            synchronized (lifecycleLock) {
                ensureOpen();
            }
            final MappedConfig<T> candidate;
            final boolean changed;
            try {
                candidate = map(source, mapper, type);
                synchronized (lifecycleLock) {
                    ensureOpen();
                    changed = !candidate.revision.equals(revision);
                    value = candidate.value;
                    revision = candidate.revision;
                    lastFailure = null;
                }
            } catch (RuntimeException failure) {
                lastFailure = failure;
                reportReloadFailure(failure);
                throw failure;
            }
            if (changed || notifyWhenUnchanged) {
                return notifyListeners();
            }
            return CompletableFuture.completedFuture(null);
        }
    }

    @Override
    public Disposable onChange(Runnable listener) {
        Objects.requireNonNull(listener, "listener");
        synchronized (lifecycleLock) {
            ensureOpen();
            listeners.add(listener);
        }
        Disposable registration = listeners.registration(listener);
        try {
            return owner.install(registration);
        } catch (RuntimeException failure) {
            registration.dispose();
            throw failure;
        }
    }

    public Optional<Throwable> lastFailure() {
        return Optional.ofNullable(lastFailure);
    }

    @Override
    public Optional<Throwable> lastReloadFailure() {
        return lastFailure();
    }

    @Override
    public Disposable onReloadFailure(Consumer<? super Throwable> listener) {
        Objects.requireNonNull(listener, "listener");
        synchronized (lifecycleLock) {
            ensureOpen();
        }
        Disposable registration = failureListeners.add(listener);
        try {
            return owner.install(registration);
        } catch (RuntimeException failure) {
            registration.dispose();
            throw failure;
        }
    }

    public Optional<Throwable> lastListenerFailure() {
        return Optional.ofNullable(lastListenerFailure);
    }

    @Override
    public String diagnosticName() {
        return "config";
    }

    @Override
    public Map<String, ?> diagnosticSnapshot() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("source", sourceName());
        result.put("value_type", type.getName());
        result.put("revision", revision);
        result.put("reload_failure", failureType(lastFailure));
        result.put("listener_failure", failureType(lastListenerFailure));
        synchronized (lifecycleLock) {
            result.put("closed", disposed);
        }
        return result;
    }

    private static String failureType(Throwable failure) {
        return failure == null ? "" : failure.getClass().getName();
    }

    @Override
    public void dispose() {
        Disposable currentWatcher;
        synchronized (lifecycleLock) {
            if (disposed) {
                return;
            }
            disposed = true;
            listeners.clear();
            failureListeners.clear();
            currentWatcher = watcher;
        }
        if (currentWatcher != null) {
            currentWatcher.dispose();
        }
    }

    private void reloadFromWatcher() {
        try {
            reload(false);
        } catch (RuntimeException failure) {
            // 重新加载会记录配置失败，同时保留最近一次有效值。
        }
    }

    private CompletionStage<Void> notifyListeners() {
        CompletableFuture<Void> completion = new CompletableFuture<Void>();
        try {
            listenerExecutor.accept(() -> {
                runListeners();
                Throwable failure = lastListenerFailure;
                if (failure == null) {
                    completion.complete(null);
                } else {
                    completion.completeExceptionally(failure);
                }
            });
        } catch (RuntimeException failure) {
            lastListenerFailure = failure;
            LOGGER.log(Level.SEVERE,
                    sourceName() + ": failed to dispatch reload listeners", failure);
            completion.completeExceptionally(failure);
        }
        return completion;
    }

    private void reportReloadFailure(Throwable failure) {
        LOGGER.log(Level.SEVERE, sourceName() + ": configuration reload failed;"
                + " keeping the last known good value", failure);
        for (Consumer<? super Throwable> listener : failureListeners.snapshot()) {
            try {
                listener.accept(failure);
            } catch (RuntimeException listenerFailure) {
                LOGGER.log(Level.WARNING,
                        sourceName() + ": reload failure listener failed", listenerFailure);
            }
        }
    }

    private void runListeners() {
        lastListenerFailure = null;
        ConfigException aggregate = null;
        for (Runnable listener : listeners.snapshot()) {
            synchronized (lifecycleLock) {
                if (disposed) {
                    return;
                }
            }
            try {
                listener.run();
            } catch (RuntimeException failure) {
                if (aggregate == null) {
                    aggregate = new ConfigException(
                            sourceName() + ":<root>: one or more reload listeners failed");
                }
                aggregate.addSuppressed(failure);
            }
        }
        if (aggregate != null) {
            LOGGER.log(Level.SEVERE, sourceName() + ": one or more reload listeners failed;"
                    + " the new configuration stays loaded", aggregate);
        }
        lastListenerFailure = aggregate;
    }

    private void ensureOpen() {
        if (disposed || owner.isClosed()) {
            throw new IllegalStateException("Config document is closed: " + sourceName());
        }
    }

    private static <T> MappedConfig<T> map(
            ConfigSource source,
            YamlConfigMapper mapper,
            Class<T> type
    ) {
        PreparedConfig prepared = source.prepare();
        if (prepared == null) {
            throw new ConfigException(source.sourceName() + ":<root>: source returned null prepare");
        }
        YamlDocument document = prepared.document();
        if (document == null) {
            throw new ConfigException(source.sourceName() + ":<root>: source returned null document");
        }
        T mapped = mapper.read(document.root(), type);
        prepared.commit();
        String revision = prepared.revision();
        if (revision == null) {
            throw new ConfigException(source.sourceName() + ":<root>: source returned null revision");
        }
        return new MappedConfig<T>(mapped, revision);
    }

    private static final class MappedConfig<T> {
        private final T value;
        private final String revision;

        private MappedConfig(T value, String revision) {
            this.value = value;
            this.revision = revision;
        }
    }
}
