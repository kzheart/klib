package me.kzheart.klib.script;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import me.kzheart.klib.scheduler.SchedulerFactory;
import me.kzheart.klib.scheduler.Ticks;
import me.kzheart.klib.scope.Disposable;
import me.kzheart.klib.scope.Scope;
import me.kzheart.klib.script.kether.core.ExitStatus;
import me.kzheart.klib.script.kether.core.ParsedAction;
import me.kzheart.klib.script.kether.core.QuestActionParser;

/**
 * 在 Klib 与同服 TabooLib OpenContainer 之间双向共享 Kether action。
 *
 * <p>该类不链接 TabooLib 类型。跨插件对象仅按稳定的方法和字段形状反射访问。</p>
 */
public final class TabooLibKetherInterop
        implements UnknownStatementResolver, KetherParserResolver, Disposable {

    static final String REMOTE_RESOLVE = "kether_remote_resolve";
    static final String CREATE_FRAME = "kether_create_frame";
    static final String CREATE_EXIT_STATUS = "kether_create_exit_status";
    static final String CREATE_PARSED_ACTION = "kether_create_parsed_action";
    static final String ADD_ACTION = "kether_add_action";
    static final String REMOVE_ACTION = "kether_remove_action";

    private static volatile TabooLibKetherInterop active;

    private final StatementRegistry registry;
    private final String providerName;
    private final ContainerDiscovery discovery;
    private final StatementRegistry.ListenerRegistration listener;
    private final Map<String, OpenContainer> containers =
            new LinkedHashMap<String, OpenContainer>();
    private final Map<String, ImportedRegistration> imports =
            new LinkedHashMap<String, ImportedRegistration>();
    private final Set<String> exportKeys = new java.util.LinkedHashSet<String>();
    private boolean closed;

    private TabooLibKetherInterop(
            StatementRegistry registry,
            String providerName,
            ContainerDiscovery discovery
    ) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.providerName = requireText(providerName, "providerName");
        this.discovery = Objects.requireNonNull(discovery, "discovery");
        this.listener = registry.listen(this::registrationChanged);
        for (StatementRegistry.EntryView entry : registry.sharedEntries()) {
            exportKeys.add(key(entry.namespace, entry.name));
        }
    }

    /** 使用可注入的容器发现器安装互操作管理器。 */
    public static TabooLibKetherInterop install(
            Scope scope,
            StatementRegistry registry,
            String providerName,
            ContainerDiscovery discovery
    ) {
        Objects.requireNonNull(scope, "scope");
        TabooLibKetherInterop interop = new TabooLibKetherInterop(
                registry, providerName, discovery);
        synchronized (TabooLibKetherInterop.class) {
            if (active != null && !active.closed) {
                interop.dispose();
                throw new IllegalStateException("TabooLib Kether interop is already installed");
            }
            active = interop;
        }
        try {
            scope.install(interop);
            interop.refresh();
            if (scope.findCapability(SchedulerFactory.class).isPresent()) {
                scope.every(Ticks.seconds(1), interop::refresh);
            }
            return interop;
        } catch (RuntimeException failure) {
            interop.dispose();
            throw failure;
        }
    }

    /** 使用 Bukkit 反射发现器安装；运行时仍不要求编译依赖 TabooLib。 */
    public static TabooLibKetherInterop install(
            Scope scope,
            StatementRegistry registry,
            String providerName
    ) {
        return install(scope, registry, providerName,
                ReflectiveTabooLibContainers.bukkit(providerName));
    }

    /** 供生成的 `${group}.taboolib.common.OpenAPI` 入口委派。 */
    public static OpenResult call(String channel, Object[] data) {
        TabooLibKetherInterop current = active;
        return current == null ? OpenResult.failed() : current.handle(channel, data);
    }

    /** 重新发现容器，并向后加载或换代的容器重放当前 shared action。 */
    public synchronized void refresh() {
        if (closed) {
            return;
        }
        List<? extends OpenContainer> found = discovery.discover();
        if (found == null) {
            found = Collections.emptyList();
        }
        Map<String, OpenContainer> current = new LinkedHashMap<String, OpenContainer>();
        for (OpenContainer container : found) {
            if (container == null || providerName.equals(container.name())) {
                continue;
            }
            current.put(container.name(), container);
            OpenContainer previous = containers.get(container.name());
            if (previous != container) {
                if (previous != null) {
                    invalidateImports(container.name());
                }
                publishAll(container);
            }
        }
        for (String previousName : new ArrayList<String>(containers.keySet())) {
            if (!current.containsKey(previousName)) {
                invalidateImports(previousName);
            }
        }
        containers.clear();
        containers.putAll(current);
    }

    @Override
    public QuestActionParser parser(String action, List<String> namespaces) {
        final String normalized = requireText(action, "action");
        final List<String> selected = Collections.unmodifiableList(
                new ArrayList<String>(Objects.requireNonNull(namespaces, "namespaces")));
        return new QuestActionParser() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> me.kzheart.klib.script.kether.core.QuestAction<T> resolve(
                    me.kzheart.klib.script.kether.core.QuestReader reader
            ) {
                refresh();
                RuntimeException last = null;
                for (String namespace : selected) {
                    for (OpenContainer container : snapshotContainers()) {
                        try {
                            OpenResult result = container.call(
                                    REMOTE_RESOLVE,
                                    providerName,
                                    reader,
                                    normalized,
                                    namespace);
                            if (result.isSuccessful() && result.getValue() != null) {
                                return (me.kzheart.klib.script.kether.core.QuestAction<T>)
                                        new TabooLibKetherProtocol.RemoteQuestAction<Object>(
                                                container, providerName, result.getValue());
                            }
                        } catch (RuntimeException failure) {
                            last = failure;
                        }
                    }
                }
                if (last != null) {
                    throw last;
                }
                throw new IllegalArgumentException(
                        "No shared Kether action resolved: " + normalized);
            }
        };
    }

    /** 旧执行级接口只作为清晰失败保留；协议解析始终通过 {@link #parser}。 */
    @Override
    public CompletionStage<Object> resolve(String statement, ScriptContext context) {
        CompletableFuture<Object> failed = new CompletableFuture<Object>();
        failed.completeExceptionally(new IllegalStateException(
                "TabooLib Kether actions must be resolved during parsing"));
        return failed;
    }

    @Override
    public synchronized void dispose() {
        if (closed) {
            return;
        }
        closed = true;
        listener.dispose();
        for (StatementRegistry.EntryView entry : registry.sharedEntries()) {
            for (OpenContainer container : containers.values()) {
                remove(container, entry.namespace, entry.name);
            }
        }
        for (ImportedRegistration registration : imports.values()) {
            registration.registration.dispose();
        }
        imports.clear();
        exportKeys.clear();
        containers.clear();
        synchronized (TabooLibKetherInterop.class) {
            if (active == this) {
                active = null;
            }
        }
    }

    private synchronized OpenResult handle(String channel, Object[] values) {
        if (closed || channel == null || values == null) {
            return OpenResult.failed();
        }
        if (REMOTE_RESOLVE.equals(channel)) {
            requireLength(values, 4, channel);
            OpenContainer consumer = requireContainer(String.valueOf(values[0]));
            Optional<QuestActionParser> parser = registry.resolveSharedKether(
                    String.valueOf(values[3]), String.valueOf(values[2]));
            if (!parser.isPresent()) {
                return OpenResult.failed();
            }
            return OpenResult.successful(parser.get().resolve(
                    new TabooLibKetherProtocol.RemoteQuestReader(
                            consumer, providerName, values[1])));
        }
        if (CREATE_FRAME.equals(channel)) {
            requireLength(values, 2, channel);
            OpenContainer remote = requireContainer(String.valueOf(values[0]));
            return OpenResult.successful(new TabooLibKetherProtocol.RemoteFrame(
                    remote, providerName, values[1]));
        }
        if (CREATE_PARSED_ACTION.equals(channel)) {
            requireLength(values, 3, channel);
            OpenContainer remote = requireContainer(String.valueOf(values[0]));
            return OpenResult.successful(new ParsedAction<Object>(
                    new TabooLibKetherProtocol.RemoteQuestAction<Object>(
                            remote, providerName, values[1]),
                    TabooLibKetherProtocol.properties(values[2])));
        }
        if (CREATE_EXIT_STATUS.equals(channel)) {
            requireLength(values, 3, channel);
            return OpenResult.successful(new ExitStatus(
                    (Boolean) values[0],
                    (Boolean) values[1],
                    ((Number) values[2]).longValue()));
        }
        if (ADD_ACTION.equals(channel)) {
            requireLength(values, 3, channel);
            String owner = String.valueOf(values[0]);
            OpenContainer remote = requireContainer(owner);
            String namespace = String.valueOf(values[2]);
            for (String action : strings(values[1])) {
                String key = key(namespace, action);
                ImportedRegistration previous = imports.get(key);
                if (previous != null && !previous.owner.equals(owner)) {
                    return OpenResult.failed();
                }
                imports.remove(key);
                if (previous != null) {
                    previous.registration.dispose();
                }
                StatementRegistration registration = registry.registerImportedKether(
                        namespace,
                        action,
                        new TabooLibKetherProtocol.RemoteActionParser(
                                remote, providerName, action, namespace));
                imports.put(key, new ImportedRegistration(owner, registration));
            }
            return OpenResult.successful();
        }
        if (REMOVE_ACTION.equals(channel)) {
            requireLength(values, 2, channel);
            String namespace = String.valueOf(values[1]);
            for (String action : strings(values[0])) {
                ImportedRegistration registration = imports.remove(key(namespace, action));
                if (registration != null) {
                    registration.registration.dispose();
                }
            }
            return OpenResult.successful();
        }
        return OpenResult.failed();
    }

    private synchronized void publishEffective(String namespace, String name) {
        Optional<QuestActionParser> current = registry.resolveSharedKether(namespace, name);
        for (OpenContainer container : containers.values()) {
            if (current.isPresent()) {
                add(container, namespace, name);
            } else {
                remove(container, namespace, name);
            }
        }
    }

    private synchronized void registrationChanged(
            StatementRegistry.EntryView entry,
            boolean added
    ) {
        if (closed) {
            return;
        }
        String changedKey = key(entry.namespace, entry.name);
        if (entry.shared) {
            exportKeys.add(changedKey);
        }
        if (exportKeys.contains(changedKey)) {
            publishEffective(entry.namespace, entry.name);
        }
    }

    private void publishAll(OpenContainer container) {
        for (StatementRegistry.EntryView entry : registry.sharedEntries()) {
            add(container, entry.namespace, entry.name);
        }
    }

    private void add(OpenContainer container, String namespace, String name) {
        OpenResult result = container.call(
                ADD_ACTION, providerName, new String[] {name}, namespace);
        if (!result.isSuccessful()) {
            throw new IllegalStateException(
                    "TabooLib container rejected shared action " + namespace + ':' + name
                            + " in " + container.name());
        }
    }

    private static void remove(OpenContainer container, String namespace, String name) {
        container.call(REMOVE_ACTION, new String[] {name}, namespace);
    }

    private synchronized List<OpenContainer> snapshotContainers() {
        return new ArrayList<OpenContainer>(containers.values());
    }

    private OpenContainer requireContainer(String name) {
        OpenContainer discovered = discovery.find(name);
        OpenContainer current = containers.get(name);
        if (discovered != null && discovered != current) {
            if (current != null) {
                invalidateImports(name);
            }
            containers.put(name, discovered);
            return discovered;
        }
        if (current != null) {
            return current;
        }
        if (discovered == null) {
            throw new IllegalArgumentException("Unknown TabooLib container " + name);
        }
        return discovered;
    }

    private void invalidateImports(String owner) {
        List<String> removed = new ArrayList<String>();
        for (Map.Entry<String, ImportedRegistration> entry : imports.entrySet()) {
            if (owner.equals(entry.getValue().owner)) {
                entry.getValue().registration.dispose();
                removed.add(entry.getKey());
            }
        }
        for (String key : removed) {
            imports.remove(key);
        }
    }

    private static String key(String namespace, String name) {
        return namespace.toLowerCase(java.util.Locale.ROOT) + ':'
                + name.toLowerCase(java.util.Locale.ROOT);
    }

    private static List<String> strings(Object value) {
        if (value instanceof String[]) {
            return Arrays.asList((String[]) value);
        }
        if (value instanceof Iterable) {
            List<String> result = new ArrayList<String>();
            for (Object item : (Iterable<?>) value) {
                result.add(String.valueOf(item));
            }
            return result;
        }
        if (value != null && value.getClass().isArray()) {
            List<String> result = new ArrayList<String>();
            int length = java.lang.reflect.Array.getLength(value);
            for (int index = 0; index < length; index++) {
                result.add(String.valueOf(java.lang.reflect.Array.get(value, index)));
            }
            return result;
        }
        return Collections.singletonList(String.valueOf(value));
    }

    private static void requireLength(Object[] values, int length, String channel) {
        if (values.length < length) {
            throw new IllegalArgumentException(
                    channel + " requires " + length + " arguments");
        }
    }

    private static String requireText(String value, String label) {
        Objects.requireNonNull(value, label);
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return normalized;
    }

    /** 可替换的 OpenContainer 发现边界。 */
    public interface ContainerDiscovery {
        List<? extends OpenContainer> discover();
        OpenContainer find(String name);
    }

    /** 不泄漏 TabooLib 类型的容器调用边界。 */
    public interface OpenContainer {
        String name();
        OpenResult call(String channel, Object... data);
    }

    /** 与 TabooLib OpenResult.cast 兼容的返回形状。 */
    public static final class OpenResult {
        private static final OpenResult FAILED = new OpenResult(false, null);
        private final boolean successful;
        private final Object value;

        private OpenResult(boolean successful, Object value) {
            this.successful = successful;
            this.value = value;
        }

        public static OpenResult successful() {
            return new OpenResult(true, null);
        }

        public static OpenResult successful(Object value) {
            return new OpenResult(true, value);
        }

        public static OpenResult failed() {
            return FAILED;
        }

        public boolean isSuccessful() {
            return successful;
        }

        public boolean isFailed() {
            return !successful;
        }

        public Object getValue() {
            return value;
        }
    }

    private static final class ImportedRegistration {
        private final String owner;
        private final StatementRegistration registration;

        private ImportedRegistration(String owner, StatementRegistration registration) {
            this.owner = owner;
            this.registration = registration;
        }
    }
}
