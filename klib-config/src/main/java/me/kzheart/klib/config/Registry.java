package me.kzheart.klib.config;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import me.kzheart.klib.diagnostic.DiagnosticSource;
import me.kzheart.klib.scheduler.SchedulerFactory;
import me.kzheart.klib.scheduler.Ticks;
import me.kzheart.klib.scope.Disposable;
import me.kzheart.klib.scope.Scope;

/** 以目录为存储、原子更新的 YAML 值注册表。 */
public final class Registry<T> implements Disposable, ReloadFailureSource, DiagnosticSource {
    private static final Logger LOGGER = Logger.getLogger(Registry.class.getName());
    private final Object lifecycleLock = new Object();
    private final Object reloadMutex = new Object();
    private final Scope owner;
    private final Path directory;
    private final Class<T> type;
    private final Function<? super T, String> idExtractor;
    private final YamlConfigMapper mapper;
    private final Consumer<Runnable> listenerExecutor;
    private final ListenerList listeners = new ListenerList();
    private final FailureListenerList failureListeners = new FailureListenerList();

    private volatile Map<String, T> snapshot;
    private volatile Throwable lastFailure;
    private volatile Throwable lastListenerFailure;
    private Disposable watcher;
    private boolean disposed;

    private Registry(
            Scope owner,
            Path directory,
            Class<T> type,
            Function<? super T, String> idExtractor,
            YamlConfigMapper mapper,
            Consumer<Runnable> listenerExecutor,
            Map<String, T> initial
    ) {
        this.owner = owner;
        this.directory = directory;
        this.type = type;
        this.idExtractor = idExtractor;
        this.mapper = mapper;
        this.listenerExecutor = listenerExecutor;
        this.snapshot = initial;
    }

    public static <T> Registry<T> open(
            Scope owner,
            Path directory,
            Class<T> type,
            Function<? super T, String> idExtractor,
            YamlConfigMapper mapper
    ) {
        return open(owner, directory, type, idExtractor, mapper, true);
    }

    public static <T> Registry<T> open(
            Scope owner,
            Path directory,
            Class<T> type,
            Function<? super T, String> idExtractor,
            YamlConfigMapper mapper,
            boolean watching
    ) {
        Objects.requireNonNull(owner, "owner");
        return open(owner, directory, type, idExtractor, mapper, watching, listenerExecutor(owner));
    }

    public static <T> Registry<T> open(
            Scope owner,
            Path directory,
            Class<T> type,
            Function<? super T, String> idExtractor,
            YamlConfigMapper mapper,
            boolean watching,
            Consumer<Runnable> listenerExecutor
    ) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(idExtractor, "idExtractor");
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(listenerExecutor, "listenerExecutor");
        if (owner.isClosed()) {
            throw new IllegalStateException("Cannot open registry in closed scope: " + owner.name());
        }
        Path normalized = directory.toAbsolutePath().normalize();
        Map<String, T> initial = loadSnapshot(normalized, type, idExtractor, mapper);
        Registry<T> registry = new Registry<T>(
                owner, normalized, type, idExtractor, mapper, listenerExecutor, initial);
        try {
            owner.install(registry);
            if (watching) {
                Disposable watchHandle = FileWatchHandle.start(
                        normalized,
                        Registry::isYaml,
                        registry::reloadFromWatcher);
                registry.watcher = watchHandle;
                try {
                    owner.install(watchHandle);
                } catch (RuntimeException failure) {
                    watchHandle.dispose();
                    throw failure;
                }
            }
            return registry;
        } catch (RuntimeException failure) {
            registry.dispose();
            throw failure;
        }
    }

    /** 与 YamlConfigCapability 保持一致：存在作用域调度器时，通过它派发监听器。 */
    private static Consumer<Runnable> listenerExecutor(Scope owner) {
        if (!owner.findCapability(SchedulerFactory.class).isPresent()) {
            return Runnable::run;
        }
        SchedulerFactory factory = owner.requireCapability(SchedulerFactory.class);
        return listener -> factory.forScope(owner).after(Ticks.of(0L), listener);
    }

    public Map<String, T> snapshot() {
        return snapshot;
    }

    public Optional<T> find(String id) {
        return Optional.ofNullable(snapshot.get(id));
    }

    public Collection<T> values() {
        return snapshot.values();
    }

    public void reload() {
        synchronized (reloadMutex) {
            synchronized (lifecycleLock) {
                ensureOpen();
            }
            final Map<String, T> candidate;
            try {
                candidate = loadSnapshot(directory, type, idExtractor, mapper);
                synchronized (lifecycleLock) {
                    ensureOpen();
                    snapshot = candidate;
                    lastFailure = null;
                }
            } catch (RuntimeException failure) {
                lastFailure = failure;
                reportReloadFailure(failure);
                throw failure;
            }
            notifyListeners();
        }
    }

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
        return "config-registry";
    }

    @Override
    public Map<String, ?> diagnosticSnapshot() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("directory", directory.toString());
        result.put("value_type", type.getName());
        result.put("entries", snapshot.size());
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
            reload();
        } catch (RuntimeException failure) {
            // 重新加载会记录配置失败，同时保留最近一次有效快照。
        }
    }

    private void notifyListeners() {
        try {
            listenerExecutor.accept(this::runListeners);
        } catch (RuntimeException failure) {
            lastListenerFailure = failure;
            LOGGER.log(Level.SEVERE,
                    directory + ": failed to dispatch registry listeners", failure);
        }
    }

    private void reportReloadFailure(Throwable failure) {
        LOGGER.log(Level.SEVERE, directory + ": registry reload failed;"
                + " keeping the last known good snapshot", failure);
        for (Consumer<? super Throwable> listener : failureListeners.snapshot()) {
            try {
                listener.accept(failure);
            } catch (RuntimeException listenerFailure) {
                LOGGER.log(Level.WARNING,
                        directory + ": reload failure listener failed", listenerFailure);
            }
        }
    }

    private void runListeners() {
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
                            directory + ": one or more registry listeners failed");
                }
                aggregate.addSuppressed(failure);
            }
        }
        if (aggregate != null) {
            LOGGER.log(Level.SEVERE, directory + ": one or more registry listeners failed;"
                    + " the new snapshot stays published", aggregate);
        }
        lastListenerFailure = aggregate;
    }

    private void ensureOpen() {
        if (disposed || owner.isClosed()) {
            throw new IllegalStateException("Registry is closed: " + directory);
        }
    }

    private static <T> Map<String, T> loadSnapshot(
            Path directory,
            Class<T> type,
            Function<? super T, String> idExtractor,
            YamlConfigMapper mapper
    ) {
        try {
            Files.createDirectories(directory);
            List<Path> files = new ArrayList<Path>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
                for (Path file : stream) {
                    if (Files.isRegularFile(file) && isYaml(file)) {
                        files.add(file);
                    }
                }
            }
            Collections.sort(files, Comparator.comparing(path -> path.getFileName().toString()));

            Map<String, T> loaded = new LinkedHashMap<String, T>();
            for (Path file : files) {
                String content = FileConfigSource.readStrictUtf8(file);
                T value = mapper.read(YamlDocument.parse(file.toString(), content).root(), type);
                final String extracted;
                try {
                    extracted = idExtractor.apply(value);
                } catch (RuntimeException failure) {
                    throw new ConfigException(file + ":<root>: registry ID extraction failed", failure);
                }
                if (extracted == null || extracted.trim().isEmpty()) {
                    throw new ConfigException(file + ":<root>: registry ID must not be blank");
                }
                String id = extracted.trim();
                if (loaded.put(id, value) != null) {
                    throw new ConfigException(
                            directory + ":<root>: duplicate registry ID '" + id + "'");
                }
            }
            return Collections.unmodifiableMap(loaded);
        } catch (IOException failure) {
            throw new ConfigException(directory + ": cannot load registry", failure);
        }
    }

    private static boolean isYaml(Path file) {
        String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".yml") || name.endsWith(".yaml");
    }
}
