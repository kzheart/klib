package me.kzheart.klib.script;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import me.kzheart.klib.scope.Scope;

/** 注册项由作用域持有的线程安全命名空间注册表。 */
public final class StatementRegistry {

    private static final String DEFAULT_NAMESPACE = "global";

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final Map<String, Deque<Entry>> entries = new LinkedHashMap<String, Deque<Entry>>();
    private boolean builtinsInstalled;
    private long version;
    private final List<ChangeListener> listeners = new ArrayList<ChangeListener>();

    boolean beginBuiltinInstall() {
        lock.writeLock().lock();
        try {
            if (builtinsInstalled) {
                return false;
            }
            builtinsInstalled = true;
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public StatementRegistration register(
            Scope scope,
            String name,
            QuestActionParser parser
    ) {
        return register(scope, DEFAULT_NAMESPACE, name, parser);
    }

    public StatementRegistration register(
            Scope scope,
            String namespace,
            String name,
            QuestActionParser parser
    ) {
        Objects.requireNonNull(scope, "scope");
        final String normalizedNamespace = normalize(namespace, "namespace");
        final String normalizedName = normalize(name, "name");
        final Entry entry = add(normalizedNamespace, normalizedName, parser, null, false, false);
        Registration registration = new Registration(entry);
        try {
            return scope.install(registration);
        } catch (RuntimeException failure) {
            registration.dispose();
            throw failure;
        }
    }

    /** 注册直接消费完整 Kether {@code QuestReader} 的本地语句。 */
    public StatementRegistration registerKether(
            Scope scope,
            String namespace,
            String name,
            me.kzheart.klib.script.kether.core.QuestActionParser parser
    ) {
        return registerKether(scope, namespace, name, parser, false);
    }

    /** 注册可同时发布给 TabooLib OpenContainer 的完整 Kether 语句。 */
    public StatementRegistration registerShared(
            Scope scope,
            String namespace,
            String name,
            me.kzheart.klib.script.kether.core.QuestActionParser parser
    ) {
        return registerKether(scope, namespace, name, parser, true);
    }

    private StatementRegistration registerKether(
            Scope scope,
            String namespace,
            String name,
            me.kzheart.klib.script.kether.core.QuestActionParser parser,
            boolean shared
    ) {
        Objects.requireNonNull(scope, "scope");
        Entry entry = add(
                normalize(namespace, "namespace"),
                normalize(name, "name"),
                null,
                Objects.requireNonNull(parser, "parser"),
                false,
                shared);
        Registration registration = new Registration(entry);
        try {
            return scope.install(registration);
        } catch (RuntimeException failure) {
            registration.dispose();
            throw failure;
        }
    }

    StatementRegistration registerImportedKether(
            String namespace,
            String name,
            me.kzheart.klib.script.kether.core.QuestActionParser parser
    ) {
        Entry entry = add(
                normalize(namespace, "namespace"),
                normalize(name, "name"),
                null,
                Objects.requireNonNull(parser, "parser"),
                false,
                false);
        return new Registration(entry);
    }

    StatementRegistration registerBuiltin(
            String namespace,
            String name,
            QuestActionParser parser
    ) {
        Entry entry = add(normalize(namespace, "namespace"), normalize(name, "name"), parser, null, true, false);
        return new Registration(entry);
    }

    public Optional<QuestActionParser> resolve(String name, List<String> namespaces) {
        Objects.requireNonNull(namespaces, "namespaces");
        String normalizedName = normalize(name, "name");
        int separator = namespaceSeparator(normalizedName);
        if (separator > 0) {
            return resolveExact(
                    normalizedName.substring(0, separator),
                    normalizedName.substring(separator + 1));
        }
        for (String namespace : namespaces) {
            Optional<QuestActionParser> parser = resolveExact(namespace, normalizedName);
            if (parser.isPresent()) {
                return parser;
            }
        }
        return resolveExact(DEFAULT_NAMESPACE, normalizedName);
    }

    public List<String> registeredNames() {
        return snapshot().registeredNames();
    }

    long version() {
        lock.readLock().lock();
        try {
            return version;
        } finally {
            lock.readLock().unlock();
        }
    }

    Snapshot snapshot() {
        lock.readLock().lock();
        try {
            List<String> names = new ArrayList<String>();
            List<EntryView> activeEntries = new ArrayList<EntryView>();
            for (Map.Entry<String, Deque<Entry>> entry : entries.entrySet()) {
                if (!entry.getValue().isEmpty()) {
                    names.add(entry.getKey());
                    activeEntries.add(entry.getValue().peekLast().view());
                }
            }
            return new Snapshot(version, names, activeEntries);
        } finally {
            lock.readLock().unlock();
        }
    }

    private Entry add(
            String namespace,
            String name,
            QuestActionParser parser,
            me.kzheart.klib.script.kether.core.QuestActionParser ketherParser,
            boolean builtin,
            boolean shared
    ) {
        if ((parser == null) == (ketherParser == null)) {
            throw new IllegalArgumentException(
                    "exactly one of parser and ketherParser must be provided");
        }
        Entry entry = new Entry(namespace, name, parser, ketherParser, builtin, shared);
        boolean changed = false;
        lock.writeLock().lock();
        try {
            String key = key(namespace, name);
            Deque<Entry> stack = entries.get(key);
            if (stack == null) {
                stack = new ArrayDeque<Entry>();
                entries.put(key, stack);
            }
            if (builtin) {
                for (Entry current : stack) {
                    if (current.builtin) {
                        return current;
                    }
                }
                stack.addFirst(entry);
            } else {
                stack.addLast(entry);
            }
            version++;
            changed = true;
        } finally {
            lock.writeLock().unlock();
        }
        if (changed) {
            notifyListeners(entry, true);
        }
        return entry;
    }

    private Optional<QuestActionParser> resolveExact(String namespace, String name) {
        String key = key(normalize(namespace, "namespace"), normalize(name, "name"));
        lock.readLock().lock();
        try {
            Deque<Entry> stack = entries.get(key);
            return stack == null || stack.isEmpty() || stack.peekLast().parser == null
                    ? Optional.<QuestActionParser>empty()
                    : Optional.of(stack.peekLast().parser);
        } finally {
            lock.readLock().unlock();
        }
    }

    private void remove(Entry entry) {
        boolean changed = false;
        lock.writeLock().lock();
        try {
            String key = key(entry.namespace, entry.name);
            Deque<Entry> stack = entries.get(key);
            if (stack != null && stack.remove(entry)) {
                if (stack.isEmpty()) {
                    entries.remove(key);
                }
                version++;
                changed = true;
            }
        } finally {
            lock.writeLock().unlock();
        }
        if (changed) {
            notifyListeners(entry, false);
        }
    }

    ListenerRegistration listen(ChangeListener listener) {
        Objects.requireNonNull(listener, "listener");
        lock.writeLock().lock();
        try {
            listeners.add(listener);
            return new ListenerRegistration(listener);
        } finally {
            lock.writeLock().unlock();
        }
    }

    List<EntryView> sharedEntries() {
        lock.readLock().lock();
        try {
            List<EntryView> result = new ArrayList<EntryView>();
            for (Deque<Entry> stack : entries.values()) {
                Entry entry = stack.peekLast();
                if (entry != null && entry.shared && entry.ketherParser != null) {
                    result.add(entry.view());
                }
            }
            return result;
        } finally {
            lock.readLock().unlock();
        }
    }

    Optional<me.kzheart.klib.script.kether.core.QuestActionParser> resolveSharedKether(
            String namespace,
            String name
    ) {
        lock.readLock().lock();
        try {
            Deque<Entry> stack = entries.get(key(
                    normalize(namespace, "namespace"), normalize(name, "name")));
            Entry entry = stack == null ? null : stack.peekLast();
            return entry != null && entry.shared && entry.ketherParser != null
                    ? Optional.of(entry.ketherParser)
                    : Optional.<me.kzheart.klib.script.kether.core.QuestActionParser>empty();
        } finally {
            lock.readLock().unlock();
        }
    }

    private void notifyListeners(Entry entry, boolean added) {
        EntryView view = entry.view();
        List<ChangeListener> snapshot;
        lock.readLock().lock();
        try {
            snapshot = new ArrayList<ChangeListener>(listeners);
        } finally {
            lock.readLock().unlock();
        }
        for (ChangeListener listener : snapshot) {
            listener.changed(view, added);
        }
    }

    private static int namespaceSeparator(String name) {
        int colon = name.indexOf(':');
        return colon >= 0 ? colon : name.indexOf('.');
    }

    private static String key(String namespace, String name) {
        return namespace + ':' + name;
    }

    private static String normalize(String value, String label) {
        Objects.requireNonNull(value, label);
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return normalized;
    }

    private static final class Entry {
        private final String namespace;
        private final String name;
        private final QuestActionParser parser;
        private final me.kzheart.klib.script.kether.core.QuestActionParser ketherParser;
        private final boolean builtin;
        private final boolean shared;

        private Entry(
                String namespace,
                String name,
                QuestActionParser parser,
                me.kzheart.klib.script.kether.core.QuestActionParser ketherParser,
                boolean builtin,
                boolean shared
        ) {
            this.namespace = namespace;
            this.name = name;
            this.parser = parser;
            this.ketherParser = ketherParser;
            this.builtin = builtin;
            this.shared = shared;
        }

        private EntryView view() {
            return new EntryView(namespace, name, parser, ketherParser, shared);
        }
    }

    static final class EntryView {
        final String namespace;
        final String name;
        final QuestActionParser parser;
        final me.kzheart.klib.script.kether.core.QuestActionParser ketherParser;
        final boolean shared;

        EntryView(String namespace, String name, QuestActionParser parser,
                me.kzheart.klib.script.kether.core.QuestActionParser ketherParser,
                boolean shared) {
            this.namespace = namespace;
            this.name = name;
            this.parser = parser;
            this.ketherParser = ketherParser;
            this.shared = shared;
        }
    }

    interface ChangeListener {
        void changed(EntryView entry, boolean added);
    }

    final class ListenerRegistration implements me.kzheart.klib.scope.Disposable {
        private final ChangeListener listener;
        private boolean active = true;

        ListenerRegistration(ChangeListener listener) {
            this.listener = listener;
        }

        @Override
        public void dispose() {
            lock.writeLock().lock();
            try {
                if (active) {
                    active = false;
                    listeners.remove(listener);
                }
            } finally {
                lock.writeLock().unlock();
            }
        }
    }

    static final class Snapshot {
        private final long version;
        private final List<String> registeredNames;
        private final List<EntryView> entries;

        private Snapshot(long version, List<String> registeredNames, List<EntryView> entries) {
            this.version = version;
            this.registeredNames = java.util.Collections.unmodifiableList(registeredNames);
            this.entries = java.util.Collections.unmodifiableList(entries);
        }

        long version() {
            return version;
        }

        List<String> registeredNames() {
            return registeredNames;
        }

        List<EntryView> entries() {
            return entries;
        }
    }

    private final class Registration implements StatementRegistration {
        private final Entry entry;
        private boolean registered = true;

        private Registration(Entry entry) {
            this.entry = entry;
        }

        @Override
        public String namespace() {
            return entry.namespace;
        }

        @Override
        public String name() {
            return entry.name;
        }

        @Override
        public boolean isRegistered() {
            synchronized (this) {
                return registered;
            }
        }

        @Override
        public void dispose() {
            synchronized (this) {
                if (!registered) {
                    return;
                }
                registered = false;
            }
            remove(entry);
        }
    }
}
