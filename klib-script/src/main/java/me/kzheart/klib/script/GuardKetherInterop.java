package me.kzheart.klib.script;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;
import me.kzheart.klib.guard.PluginHost;
import me.kzheart.klib.guard.kether.KetherInteropEndpoint;
import me.kzheart.klib.guard.kether.KetherInteropPeer;
import me.kzheart.klib.guard.kether.KetherInteropProtocol;
import me.kzheart.klib.guard.kether.KetherInteropRegistration;
import me.kzheart.klib.guard.kether.KetherInteropResult;
import me.kzheart.klib.scope.Disposable;
import me.kzheart.klib.scope.Scope;
import me.kzheart.klib.script.TabooLibKetherInterop.OpenContainer;
import me.kzheart.klib.script.TabooLibKetherInterop.OpenResult;
import me.kzheart.klib.script.kether.core.QuestAction;
import me.kzheart.klib.script.kether.core.QuestActionParser;

/**
 * 通过 Guard 门户 Broker 双向共享商品的 Kether action。
 *
 * <p>商品私有的 reader、action 与 frame 只在本适配器内出现；父加载器仅看到 action 句柄、
 * JDK {@link CompletionStage} 和外部容器的反射对象。</p>
 */
public final class GuardKetherInterop
        implements UnknownStatementResolver, KetherParserResolver, KetherInteropEndpoint, Disposable {

    private final StatementRegistry registry;
    private final String productId;
    private final StatementRegistry.ListenerRegistration listener;
    private final Set<String> exportKeys = new LinkedHashSet<String>();
    private final Map<String, ImportedRegistration> imports =
            new LinkedHashMap<String, ImportedRegistration>();
    private final Map<Long, QuestAction<?>> actions =
            new LinkedHashMap<Long, QuestAction<?>>();
    private final Map<CompletableFuture<?>, CompletableFuture<Object>> running =
            new LinkedHashMap<CompletableFuture<?>, CompletableFuture<Object>>();
    private final AtomicLong nextHandle = new AtomicLong();

    private KetherInteropRegistration registration;
    private boolean active = true;

    private GuardKetherInterop(StatementRegistry registry, String productId) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.productId = requireText(productId, "productId");
        this.listener = registry.listen(this::registrationChanged);
        for (StatementRegistry.EntryView entry : registry.sharedEntries()) {
            exportKeys.add(key(entry.namespace, entry.name));
        }
    }

    /** 将商品注册表绑定到当前 {@link PluginHost} 的已认证商品代次。 */
    public static GuardKetherInterop install(
            Scope scope,
            StatementRegistry registry,
            PluginHost host
    ) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(host, "host");
        GuardKetherInterop interop = new GuardKetherInterop(registry, host.productId());
        try {
            interop.registration = host.ketherInteropBroker().attach(
                    host.productId(),
                    host.generation(),
                    registry.getClass().getClassLoader(),
                    interop);
            scope.install(interop);
            interop.publishAll();
            return interop;
        } catch (RuntimeException failure) {
            interop.dispose();
            throw failure;
        }
    }

    @Override
    public synchronized void addActions(
            KetherInteropPeer peer,
            String namespace,
            String[] actionNames
    ) {
        ensureActive();
        Objects.requireNonNull(peer, "peer");
        String normalizedNamespace = requireText(namespace, "namespace");
        PeerContainer container = new PeerContainer(peer);
        for (String action : requireActions(actionNames)) {
            String importedKey = key(normalizedNamespace, action);
            ImportedRegistration previous = imports.get(importedKey);
            if (previous != null && !previous.owner.equals(peer.name())) {
                throw new IllegalStateException(
                        "External Kether action conflict: " + normalizedNamespace + ':' + action);
            }
            if (previous != null) {
                previous.registration.dispose();
            }
            QuestActionParser parser = new TabooLibKetherProtocol.RemoteActionParser(
                    container, KetherInteropProtocol.PROVIDER_NAME, action, normalizedNamespace);
            StatementRegistration imported = registry.registerImportedKether(
                    normalizedNamespace,
                    action,
                    parser);
            imports.put(importedKey, new ImportedRegistration(peer.name(), imported, parser));
        }
    }

    @Override
    public synchronized void removeActions(
            KetherInteropPeer peer,
            String namespace,
            String[] actionNames
    ) {
        if (!active) {
            return;
        }
        Objects.requireNonNull(peer, "peer");
        String normalizedNamespace = requireText(namespace, "namespace");
        for (String action : requireActions(actionNames)) {
            String importedKey = key(normalizedNamespace, action);
            ImportedRegistration imported = imports.get(importedKey);
            if (imported != null && imported.owner.equals(peer.name())) {
                imports.remove(importedKey);
                imported.registration.dispose();
            }
        }
    }

    @Override
    public synchronized long resolve(
            KetherInteropPeer peer,
            String consumerName,
            Object reader,
            String action,
            String namespace
    ) {
        ensureActive();
        Objects.requireNonNull(peer, "peer");
        requireText(consumerName, "consumerName");
        QuestActionParser parser = registry.resolveSharedKether(namespace, action)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No shared Kether action: " + namespace + ':' + action));
        QuestAction<?> resolved = parser.resolve(new TabooLibKetherProtocol.RemoteQuestReader(
                new PeerContainer(peer), KetherInteropProtocol.PROVIDER_NAME,
                Objects.requireNonNull(reader, "reader")));
        long handle = nextHandle.incrementAndGet();
        if (handle <= 0L) {
            throw new IllegalStateException("Kether action handle space exhausted");
        }
        actions.put(Long.valueOf(handle), Objects.requireNonNull(resolved, "resolved action"));
        return handle;
    }

    @Override
    @SuppressWarnings("unchecked")
    public synchronized CompletionStage<Object> process(
            KetherInteropPeer peer,
            String consumerName,
            long actionHandle,
            Object frame
    ) {
        if (!active) {
            return failedFuture(new IllegalStateException(
                    "Kether product generation is no longer active: " + productId));
        }
        Objects.requireNonNull(peer, "peer");
        requireText(consumerName, "consumerName");
        QuestAction<Object> action = (QuestAction<Object>) actions.get(Long.valueOf(actionHandle));
        if (action == null) {
            return failedFuture(new IllegalStateException(
                    "Unknown or stale Kether action handle: " + actionHandle));
        }
        CompletableFuture<Object> source;
        try {
            source = action.process(new TabooLibKetherProtocol.RemoteFrame(
                    new PeerContainer(peer), KetherInteropProtocol.PROVIDER_NAME,
                    Objects.requireNonNull(frame, "frame")));
        } catch (RuntimeException failure) {
            return failedFuture(failure);
        }
        if (source == null) {
            return failedFuture(new IllegalStateException("Kether action returned null future"));
        }
        final CompletableFuture<Object> result = new CompletableFuture<Object>();
        running.put(source, result);
        source.whenComplete((value, failure) -> complete(source, result, value, failure));
        return result;
    }

    @Override
    public synchronized void release(long actionHandle) {
        actions.remove(Long.valueOf(actionHandle));
    }

    @Override
    public QuestActionParser parser(String action, List<String> namespaces) {
        final String normalizedAction = requireText(action, "action");
        final List<String> selected = new ArrayList<String>(
                Objects.requireNonNull(namespaces, "namespaces"));
        return new QuestActionParser() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> QuestAction<T> resolve(me.kzheart.klib.script.kether.core.QuestReader reader) {
                synchronized (GuardKetherInterop.this) {
                    ensureActive();
                    for (String namespace : selected) {
                        Optional<QuestActionParser> imported = importedParser(namespace, normalizedAction);
                        if (imported.isPresent()) {
                            return (QuestAction<T>) imported.get().resolve(reader);
                        }
                    }
                }
                throw new IllegalArgumentException(
                        "No external Kether action resolved: " + normalizedAction);
            }
        };
    }

    @Override
    public CompletionStage<Object> resolve(String statement, ScriptContext context) {
        return failedFuture(new IllegalStateException(
                "Guard Kether actions must be resolved during parsing"));
    }

    @Override
    public synchronized void dispose() {
        if (!active) {
            return;
        }
        active = false;
        listener.dispose();
        KetherInteropRegistration current = registration;
        registration = null;
        if (current != null) {
            current.close();
        }
        for (ImportedRegistration imported : imports.values()) {
            imported.registration.dispose();
        }
        imports.clear();
        exportKeys.clear();
        actions.clear();
        IllegalStateException stale = new IllegalStateException(
                "Kether product generation is no longer active: " + productId);
        List<Map.Entry<CompletableFuture<?>, CompletableFuture<Object>>> pending =
                new ArrayList<Map.Entry<CompletableFuture<?>, CompletableFuture<Object>>>(
                        running.entrySet());
        running.clear();
        for (Map.Entry<CompletableFuture<?>, CompletableFuture<Object>> entry : pending) {
            entry.getValue().completeExceptionally(stale);
            entry.getKey().cancel(true);
        }
    }

    @Override
    public void close() {
        dispose();
    }

    private synchronized void registrationChanged(StatementRegistry.EntryView entry, boolean added) {
        if (!active || registration == null) {
            return;
        }
        String exportKey = key(entry.namespace, entry.name);
        if (entry.shared) {
            exportKeys.add(exportKey);
        }
        if (!exportKeys.contains(exportKey)) {
            return;
        }
        if (registry.resolveSharedKether(entry.namespace, entry.name).isPresent()) {
            registration.publish(entry.namespace, entry.name);
        } else {
            registration.withdraw(entry.namespace, entry.name);
        }
    }

    private synchronized void publishAll() {
        ensureActive();
        for (StatementRegistry.EntryView entry : registry.sharedEntries()) {
            registration.publish(entry.namespace, entry.name);
        }
    }

    private Optional<QuestActionParser> importedParser(String namespace, String action) {
        ImportedRegistration imported = imports.get(key(namespace, action));
        if (imported == null || !imported.registration.isRegistered()) {
            return Optional.empty();
        }
        return registry.resolveSharedKether(namespace, action).isPresent()
                ? Optional.<QuestActionParser>empty()
                : Optional.of(imported.parser);
    }

    private synchronized void complete(
            CompletableFuture<?> source,
            CompletableFuture<Object> result,
            Object value,
            Throwable failure
    ) {
        running.remove(source);
        if (!active) {
            result.completeExceptionally(new IllegalStateException(
                    "Kether product generation is no longer active: " + productId));
        } else if (failure == null) {
            try {
                result.complete(boundaryValue(
                        value, new IdentityHashMap<Object, Boolean>(), 0));
            } catch (RuntimeException invalid) {
                result.completeExceptionally(invalid);
            }
        } else {
            result.completeExceptionally(failure);
        }
    }

    private Object boundaryValue(
            Object value,
            IdentityHashMap<Object, Boolean> visited,
            int depth) {
        if (value == null) {
            return value;
        }
        Class<?> type = value.getClass();
        if (type == String.class || type == Boolean.class || type == Byte.class
                || type == Short.class || type == Integer.class || type == Long.class
                || type == Float.class || type == Double.class || type == Character.class
                || type == java.math.BigInteger.class || type == java.math.BigDecimal.class) {
            return value;
        }
        if (depth >= 16 || visited.size() >= 256) {
            throw new IllegalArgumentException("Kether result exceeds the Guard value boundary");
        }
        if (type.getClassLoader() == getClass().getClassLoader()) {
            throw new IllegalArgumentException(
                    "Kether result contains a product-private value: " + type.getName());
        }
        if (visited.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("Kether result contains a cyclic value graph");
        }
        try {
            if (value instanceof Map) {
                Map<Object, Object> copy = new LinkedHashMap<Object, Object>();
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                    copy.put(
                            boundaryValue(entry.getKey(), visited, depth + 1),
                            boundaryValue(entry.getValue(), visited, depth + 1));
                }
                return copy;
            }
            if (value instanceof Collection) {
                List<Object> copy = new ArrayList<Object>();
                for (Object item : (Collection<?>) value) {
                    copy.add(boundaryValue(item, visited, depth + 1));
                }
                return copy;
            }
            if (type.isArray()) {
                int length = Array.getLength(value);
                if (type.getComponentType().isPrimitive()) {
                    Object copy = Array.newInstance(type.getComponentType(), length);
                    System.arraycopy(value, 0, copy, 0, length);
                    return copy;
                }
                Object[] copy = new Object[length];
                for (int index = 0; index < length; index++) {
                    copy[index] = boundaryValue(Array.get(value, index), visited, depth + 1);
                }
                return copy;
            }
            return value;
        } finally {
            visited.remove(value);
        }
    }

    private void ensureActive() {
        if (!active) {
            throw new IllegalStateException(
                    "Kether product generation is no longer active: " + productId);
        }
    }

    private static List<String> requireActions(String[] values) {
        Objects.requireNonNull(values, "actions");
        List<String> result = new ArrayList<String>();
        for (String value : values) {
            result.add(requireText(value, "action"));
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("actions must not be empty");
        }
        return result;
    }

    private static String key(String namespace, String action) {
        return requireText(namespace, "namespace").toLowerCase(java.util.Locale.ROOT) + ':'
                + requireText(action, "action").toLowerCase(java.util.Locale.ROOT);
    }

    private static String requireText(String value, String label) {
        Objects.requireNonNull(value, label);
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return normalized;
    }

    private static CompletionStage<Object> failedFuture(Throwable failure) {
        CompletableFuture<Object> result = new CompletableFuture<Object>();
        result.completeExceptionally(failure);
        return result;
    }

    private static final class PeerContainer implements OpenContainer {
        private final KetherInteropPeer peer;

        private PeerContainer(KetherInteropPeer peer) {
            this.peer = peer;
        }

        @Override
        public String name() {
            return peer.name();
        }

        @Override
        public OpenResult call(String channel, Object... data) {
            KetherInteropResult result = peer.call(channel, data);
            return result != null && result.isSuccessful()
                    ? OpenResult.successful(result.getValue())
                    : OpenResult.failed();
        }
    }

    private static final class ImportedRegistration {
        private final String owner;
        private final StatementRegistration registration;
        private final QuestActionParser parser;

        private ImportedRegistration(
                String owner,
                StatementRegistration registration,
                QuestActionParser parser
        ) {
            this.owner = owner;
            this.registration = registration;
            this.parser = parser;
        }
    }
}
