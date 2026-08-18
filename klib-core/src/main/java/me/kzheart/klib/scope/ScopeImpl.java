package me.kzheart.klib.scope;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * 生命周期操作串行化的线程安全作用域实现。
 */
public final class ScopeImpl implements Scope {

    private enum State {
        OPEN,
        BUILDING,
        REBUILDING,
        CLOSING,
        CLOSED
    }

    private final String name;
    private final ScopeImpl parent;
    private final Consumer<? super Scope> rebuildAction;
    private final ReentrantLock lifecycleLock;
    private final List<Disposable> resources = new ArrayList<Disposable>();
    private final Map<String, ScopeImpl> children = new HashMap<String, ScopeImpl>();
    private final Map<Class<?>, Object> capabilities = new HashMap<Class<?>, Object>();

    private State state = State.OPEN;

    public ScopeImpl(String name) {
        this(name, null, null, new ReentrantLock());
    }

    private ScopeImpl(
            String name,
            ScopeImpl parent,
            Consumer<? super Scope> rebuildAction,
            ReentrantLock lifecycleLock
    ) {
        this.name = requireName(name);
        this.parent = parent;
        this.rebuildAction = rebuildAction;
        this.lifecycleLock = lifecycleLock;
    }

    /**
     * 创建根作用域，并执行会在重建时再次运行的操作。
     */
    public static ScopeImpl create(String name, Consumer<? super Scope> configure) {
        Objects.requireNonNull(configure, "configure");
        ScopeImpl scope = new ScopeImpl(name, null, configure, new ReentrantLock());
        scope.initialize();
        return scope;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean isClosed() {
        lifecycleLock.lock();
        try {
            return state == State.CLOSED;
        } finally {
            lifecycleLock.unlock();
        }
    }

    @Override
    public <T extends Disposable> T install(T resource) {
        Objects.requireNonNull(resource, "resource");
        if (resource == this) {
            throw new IllegalArgumentException("A scope cannot install itself");
        }
        lifecycleLock.lock();
        try {
            ensureRegistrationAllowed();
            resources.add(resource);
            return resource;
        } finally {
            lifecycleLock.unlock();
        }
    }

    @Override
    public void remove(Disposable resource) {
        Objects.requireNonNull(resource, "resource");
        lifecycleLock.lock();
        try {
            if (state != State.OPEN && state != State.BUILDING) {
                return;
            }
            resources.remove(resource);
        } finally {
            lifecycleLock.unlock();
        }
    }

    @Override
    public Scope scope(String childName, Consumer<? super Scope> configure) {
        Objects.requireNonNull(configure, "configure");
        String normalizedName = requireName(childName);

        lifecycleLock.lock();
        try {
            ensureRegistrationAllowed();
            if (children.containsKey(normalizedName)) {
                throw new IllegalStateException("Child scope already exists: " + normalizedName);
            }

            ScopeImpl child = new ScopeImpl(normalizedName, this, configure, lifecycleLock);
            children.put(normalizedName, child);
            resources.add(child);
            try {
                child.initialize();
                return child;
            } catch (Throwable failure) {
                resources.remove(child);
                children.remove(normalizedName);
                try {
                    child.close();
                } catch (RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                throw propagate(failure);
            }
        } finally {
            lifecycleLock.unlock();
        }
    }

    @Override
    public <T> T registerCapability(Class<T> type, T capability) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(capability, "capability");
        if (!type.isInstance(capability)) {
            throw new IllegalArgumentException("Capability does not implement " + type.getName());
        }

        lifecycleLock.lock();
        try {
            ensureRegistrationAllowed();
            if (capabilities.containsKey(type)) {
                throw new IllegalStateException("Capability already registered: " + type.getName());
            }
            capabilities.put(type, capability);
            return capability;
        } finally {
            lifecycleLock.unlock();
        }
    }

    @Override
    public <T> Optional<T> findCapability(Class<T> type) {
        Objects.requireNonNull(type, "type");

        Object local;
        lifecycleLock.lock();
        try {
            local = capabilities.get(type);
        } finally {
            lifecycleLock.unlock();
        }

        if (local != null) {
            return Optional.of(type.cast(local));
        }
        return parent == null ? Optional.<T>empty() : parent.findCapability(type);
    }

    @Override
    public <T> T requireCapability(Class<T> type) {
        Optional<T> capability = findCapability(type);
        if (!capability.isPresent()) {
            throw new IllegalStateException("Required capability is not installed: " + type.getName()
                    + "; install the module first (e.g. ConfigModule.install / CommandModule.install)"
                    + " in this scope or one of its parents before requiring it");
        }
        return capability.get();
    }

    @Override
    public void rebuild() {
        lifecycleLock.lock();
        try {
            if (state == State.CLOSED) {
                throw new IllegalStateException("Scope is closed: " + name);
            }
            if (state != State.OPEN) {
                throw new IllegalStateException("Scope lifecycle is already changing: " + name);
            }

            ScopeLifecycleException teardownFailure = closeInternal(true);

            if (teardownFailure != null) {
                throw teardownFailure;
            }
            if (rebuildAction != null) {
                initializeUnderLock();
            } else {
                state = State.OPEN;
            }
        } finally {
            lifecycleLock.unlock();
        }
    }

    @Override
    public void dispose() {
        close();
    }

    @Override
    public void close() {
        lifecycleLock.lock();
        try {
            if (state == State.CLOSED) {
                return;
            }
            if (state != State.OPEN) {
                throw new IllegalStateException("Scope lifecycle is already changing: " + name);
            }

            ScopeLifecycleException failure = closeInternal(false);
            if (failure != null) {
                throw failure;
            }
        } finally {
            lifecycleLock.unlock();
        }
    }

    private void initialize() {
        lifecycleLock.lock();
        try {
            initializeUnderLock();
        } finally {
            lifecycleLock.unlock();
        }
    }

    private void initializeUnderLock() {
        state = State.BUILDING;
        try {
            rebuildAction.accept(this);
            state = State.OPEN;
        } catch (Throwable setupFailure) {
            state = State.CLOSING;
            ScopeLifecycleException cleanupFailure = releaseResources("Failed to clean up scope " + name);
            markClosed();
            if (cleanupFailure != null) {
                setupFailure.addSuppressed(cleanupFailure);
            }
            throw propagate(setupFailure);
        }
    }

    private ScopeLifecycleException closeInternal(boolean rebuilding) {
        state = rebuilding ? State.REBUILDING : State.CLOSING;
        ScopeLifecycleException failure = releaseResources(
                (rebuilding ? "Failed to rebuild scope " : "Failed to close scope ") + name);
        if (rebuilding && failure == null) {
            state = State.OPEN;
        } else {
            // 拆卸失败会使资源图处于部分释放状态，因此按失败关闭处理。
            markClosed();
        }
        return failure;
    }

    private void markClosed() {
        state = State.CLOSED;
        if (parent != null && (parent.state == State.OPEN || parent.state == State.BUILDING)) {
            parent.resources.remove(this);
            parent.children.remove(name);
        }
    }

    private ScopeLifecycleException releaseResources(String message) {
        ScopeLifecycleException aggregate = null;
        for (int index = resources.size() - 1; index >= 0; index--) {
            try {
                resources.get(index).dispose();
            } catch (Throwable failure) {
                if (aggregate == null) {
                    aggregate = new ScopeLifecycleException(message);
                }
                aggregate.addFailure(failure);
            }
        }
        resources.clear();
        children.clear();
        capabilities.clear();
        return aggregate;
    }

    int resourceCount() {
        lifecycleLock.lock();
        try {
            return resources.size();
        } finally {
            lifecycleLock.unlock();
        }
    }

    private void ensureRegistrationAllowed() {
        if (state != State.OPEN && state != State.BUILDING) {
            throw new IllegalStateException("Cannot register resources while scope is " + state + ": " + name);
        }
    }

    private static String requireName(String value) {
        Objects.requireNonNull(value, "name");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Scope name must not be blank");
        }
        return normalized;
    }

    private static RuntimeException propagate(Throwable failure) {
        if (failure instanceof RuntimeException) {
            return (RuntimeException) failure;
        }
        if (failure instanceof Error) {
            throw (Error) failure;
        }
        return new ScopeLifecycleException("Scope configuration failed: " + failure.getMessage(), failure);
    }
}
