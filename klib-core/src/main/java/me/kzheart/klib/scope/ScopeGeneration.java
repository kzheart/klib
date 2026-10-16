package me.kzheart.klib.scope;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** Internal generation boundary shared by the Kotlin coroutine integration. */
final class ScopeGeneration {
    private final ScopeImpl owner;
    private final ScopeGeneration parent;
    private final Scope view = new View();
    private final Map<Class<?>, Disposable> attachments = new LinkedHashMap<Class<?>, Disposable>();
    private volatile boolean active = true;

    ScopeGeneration(ScopeImpl owner, ScopeGeneration parent) {
        this.owner = owner;
        this.parent = parent;
    }

    static ScopeGeneration of(Scope scope) {
        if (scope instanceof ScopeImpl) {
            return ((ScopeImpl) scope).generation();
        }
        if (scope instanceof ScopeGeneration.View) {
            return ((ScopeGeneration.View) scope).generation();
        }
        throw new IllegalArgumentException("Klib coroutines require a ScopeImpl-backed scope");
    }

    ScopeGeneration parent() { return parent; }
    Scope view() { return view; }
    boolean isActive() { return active; }

    boolean run(Runnable action) {
        return owner.runInGeneration(this, action);
    }

    <T> T call(Supplier<T> action) {
        return owner.inGeneration(this, action);
    }

    <T extends Disposable> T attachment(Class<T> type, Function<ScopeGeneration, T> factory) {
        return call(() -> {
            Disposable existing = attachments.get(type);
            if (existing != null) return type.cast(existing);
            T created = factory.apply(this);
            attachments.put(type, created);
            return created;
        });
    }

    void invalidate() { active = false; }

    ScopeLifecycleException releaseAttachments(ScopeLifecycleException aggregate, String message) {
        ArrayList<Disposable> values = new ArrayList<Disposable>(attachments.values());
        attachments.clear();
        for (int index = values.size() - 1; index >= 0; index--) {
            try {
                values.get(index).dispose();
            } catch (Throwable failure) {
                if (aggregate == null) aggregate = new ScopeLifecycleException(message);
                aggregate.addFailure(failure);
            }
        }
        return aggregate;
    }

    private final class View implements Scope {
        ScopeGeneration generation() {
            return call(() -> ScopeGeneration.this);
        }

        @Override public String name() { return owner.name(); }
        @Override public boolean isClosed() { return !active; }
        @Override public <T extends Disposable> T install(T resource) {
            return call(() -> owner.install(resource));
        }
        @Override public void remove(Disposable resource) {
            owner.removeInGeneration(ScopeGeneration.this, resource);
        }
        @Override public Scope scope(String name, Consumer<? super Scope> configure) {
            return call(() -> {
                Scope child = owner.scope(name, raw -> configure.accept(of(raw).view()));
                return of(child).view();
            });
        }
        @Override public <T> T registerCapability(Class<T> type, T capability) {
            return call(() -> owner.registerCapability(type, capability));
        }
        @Override public <T> Optional<T> findCapability(Class<T> type) {
            return call(() -> owner.findCapability(type));
        }
        @Override public <T> T requireCapability(Class<T> type) {
            return call(() -> owner.requireCapability(type));
        }
        @Override public void rebuild() {
            call(() -> { owner.rebuild(); return null; });
        }
        @Override public void dispose() { close(); }
        @Override public void close() {
            owner.closeInGeneration(ScopeGeneration.this);
        }
    }
}
