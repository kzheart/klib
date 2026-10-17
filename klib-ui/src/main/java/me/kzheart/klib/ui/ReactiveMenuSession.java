package me.kzheart.klib.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import me.kzheart.klib.scheduler.AsyncTasks;
import me.kzheart.klib.scheduler.Ticks;
import me.kzheart.klib.scope.Disposable;
import me.kzheart.klib.scope.Scope;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * One inventory opening's immutable state and display-only asynchronous reads.
 * State, reducers and views run on the server thread; only {@link #invalidate()} is thread-safe.
 * This session never cancels a supplied domain operation or owns persistent player items.
 */
public final class ReactiveMenuSession<S> {
    private final Player viewer;
    private final Function<? super ReactiveMenuSession<S>, MenuModel> view;
    private final AtomicBoolean refreshPending = new AtomicBoolean();
    private final ConcurrentHashMap<String, Read> reads = new ConcurrentHashMap<String, Read>();
    private volatile MenuHolder holder;
    private volatile boolean closed;
    private S state;
    private long revision;
    private boolean rendering;
    private volatile Thread renderingThread;
    private boolean updating;
    private boolean navigationPending;

    ReactiveMenuSession(Player viewer, S initialState,
            Function<? super ReactiveMenuSession<S>, MenuModel> view) {
        this.viewer = Objects.requireNonNull(viewer, "viewer");
        this.state = Objects.requireNonNull(initialState, "initialState");
        this.view = Objects.requireNonNull(view, "view");
    }

    void attach(MenuHolder attached) {
        if (holder != null) throw new IllegalStateException("reactive session is already attached");
        holder = Objects.requireNonNull(attached, "holder");
        attached.session().scope().install(this::release);
    }

    MenuModel buildModel() {
        MenuRenderer.ensureMainThread("构建状态菜单");
        if (rendering) throw new IllegalStateException("recursive menu render");
        rendering = true;
        renderingThread = Thread.currentThread();
        try {
            return Objects.requireNonNull(view.apply(this), "menu view returned null");
        } finally {
            renderingThread = null;
            rendering = false;
        }
    }

    /** The original Player instance, never a newer connection with the same UUID. */
    public Player viewer() {
        MenuRenderer.ensureMainThread("读取菜单玩家");
        return viewer;
    }

    /** Treat the state and everything reachable from it as immutable. */
    public S state() {
        MenuRenderer.ensureMainThread("读取菜单状态");
        if (closed) throw new IllegalStateException("reactive menu session is closed");
        return state;
    }

    public MenuHolder holder() {
        MenuRenderer.ensureMainThread("读取菜单会话");
        return requireAttached();
    }

    /** Reuses the existing MenuSession scope; closing/replacing it releases all bindings. */
    public Scope scope() {
        return holder().session().scope();
    }

    /** Equal states do nothing. A changed state invalidates reads started against the old state. */
    public void update(UnaryOperator<S> update) {
        requireMutable();
        Objects.requireNonNull(update, "update");
        updating = true;
        S replacement;
        try {
            replacement = Objects.requireNonNull(update.apply(state), "state update returned null");
        } finally {
            updating = false;
        }
        if (Objects.equals(state, replacement)) return;
        state = replacement;
        revision++;
        discardReads();
        invalidate();
    }

    /** Coalesces notifications into one render on the next tick; closed sessions ignore them. */
    public void invalidate() {
        if (Thread.currentThread() == renderingThread) {
            throw new IllegalStateException("menu views must not invalidate themselves");
        }
        MenuHolder current = holder;
        if (current == null) throw new IllegalStateException("menu view must not invalidate while opening");
        if (closed || current.session().isClosed()) return;
        if (!refreshPending.compareAndSet(false, true)) return;
        try {
            current.session().scope().after(Ticks.of(1), () -> {
                refreshPending.set(false);
                if (!isCurrent()) return;
                try {
                    current.refresh(buildModel());
                } catch (RuntimeException failure) {
                    if (!current.session().isClosed()) current.renderer.failRender(current, failure);
                    throw failure;
                } catch (Error failure) {
                    if (!current.session().isClosed()) current.renderer.failRender(current, failure);
                    throw failure;
                }
            });
        } catch (RuntimeException failure) {
            refreshPending.set(false);
            if (!closed && !current.session().isClosed()) throw failure;
        }
    }

    /** Bind an observer's release action to this opening, with no separate lifetime registry. */
    public <D extends Disposable> D bind(D subscription) {
        Objects.requireNonNull(subscription, "subscription");
        try {
            requireMutable();
            return scope().install(subscription);
        } catch (RuntimeException failure) {
            try { subscription.dispose(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    /**
     * Apply only the latest read of this key while both this opening and its state are unchanged.
     * The reducer and failure callback run on the server thread. The result is true when applied,
     * false when stale/closed, or exceptional on source/callback/dispatch failure. Ordinary result
     * callbacks have no thread guarantee. Cancelling the returned future immediately detaches this
     * observation and its queued delivery. Neither cancellation nor supersession cancels the source.
     */
    public <T> CompletionStage<Boolean> readLatest(String key, CompletionStage<T> source,
            BiFunction<? super S, ? super T, ? extends S> reducer,
            Consumer<? super Throwable> onFailure) {
        requireMutable();
        Objects.requireNonNull(key, "key");
        if (key.trim().isEmpty()) throw new IllegalArgumentException("read key must not be blank");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(reducer, "reducer");
        Objects.requireNonNull(onFailure, "onFailure");
        final Read read = new Read(revision);
        read.result.whenComplete((applied, failure) -> {
            reads.remove(key, read);
            read.releaseDelivery();
        });
        Read previous = reads.put(key, read);
        if (previous != null) previous.discard();
        try {
            CompletionStage<Outcome<T>> outcome = source.handle(Outcome<T>::new);
            CompletableFuture<Boolean> delivery = scope().submitSync(outcome, completed -> {
                if (read.result.isDone() || !isCurrent() || revision != read.revision
                        || !reads.remove(key, read)) return Boolean.FALSE;
                if (completed.failure != null) {
                    Throwable failure = AsyncTasks.unwrap(completed.failure);
                    try {
                        onFailure.accept(failure);
                    } catch (Throwable callbackFailure) {
                        if (callbackFailure != failure) failure.addSuppressed(callbackFailure);
                    }
                    throw new CompletionException(failure);
                }
                update(current -> reducer.apply(current, completed.value));
                return Boolean.TRUE;
            });
            read.attachDelivery(delivery);
            delivery.whenComplete((applied, failure) -> {
                reads.remove(key, read);
                if (failure == null) read.result.complete(applied);
                else if (closed || holder.session().isClosed()
                        || AsyncTasks.unwrap(failure) instanceof CancellationException
                        || read.result.isDone()) read.result.complete(Boolean.FALSE);
                else read.result.completeExceptionally(AsyncTasks.unwrap(failure));
            });
        } catch (RuntimeException failure) {
            reads.remove(key, read);
            read.result.completeExceptionally(failure);
        }
        return read.result;
    }

    /**
     * Run one navigation after a real one-tick boundary. Repeated requests before that tick are
     * ignored; changing state, closing, replacing or reconnecting invalidates the pending action.
     */
    public boolean navigate(Runnable navigation) {
        requireMutable();
        Objects.requireNonNull(navigation, "navigation");
        if (navigationPending) return false;
        navigationPending = true;
        discardReads();
        final long expected = revision;
        try {
            scope().after(Ticks.of(1), () -> {
                navigationPending = false;
                if (isCurrent() && revision == expected) navigation.run();
            });
        } catch (RuntimeException failure) {
            navigationPending = false;
            throw failure;
        }
        return true;
    }

    /** Must be called on the server thread because it checks the current inventory and player. */
    public boolean isCurrent() {
        MenuRenderer.ensureMainThread("检查菜单会话");
        MenuHolder current = holder;
        return !closed && current != null && !current.session().isClosed()
                && viewer.isOnline()
                && (Bukkit.getServer() == null || Bukkit.getPlayer(viewer.getUniqueId()) == viewer)
                // Bukkit may wrap the same backing inventory in a different Inventory object.
                // The unique holder identifies this opening across those wrappers.
                && viewer.getOpenInventory().getTopInventory().getHolder() == current;
    }

    private MenuHolder requireAttached() {
        MenuHolder current = holder;
        if (current == null) throw new IllegalStateException("menu view cannot use an unattached session");
        return current;
    }

    private void requireMutable() {
        MenuRenderer.ensureMainThread("修改菜单状态");
        if (rendering || updating) throw new IllegalStateException("menu views and state reducers must be side-effect free");
        requireAttached();
        if (!isCurrent()) throw new IllegalStateException("reactive menu session is no longer current");
    }

    private void release() {
        closed = true;
        refreshPending.set(false);
        discardReads();
    }

    private void discardReads() {
        List<Read> discarded = new ArrayList<Read>(reads.values());
        reads.clear();
        for (Read read : discarded) read.discard();
    }

    private static final class Read {
        final long revision;
        final CompletableFuture<Boolean> result = new CompletableFuture<Boolean>();
        final AtomicReference<CompletableFuture<?>> delivery =
                new AtomicReference<CompletableFuture<?>>();

        Read(long revision) { this.revision = revision; }

        void attachDelivery(CompletableFuture<?> task) {
            delivery.set(task);
            // Completion can happen before publication (for example a reentrant source callback).
            if (result.isDone()) releaseDelivery();
        }

        void releaseDelivery() {
            CompletableFuture<?> pending = delivery.getAndSet(null);
            if (pending != null) pending.cancel(false);
        }

        void discard() {
            result.complete(Boolean.FALSE);
        }
    }

    private static final class Outcome<T> {
        final T value;
        final Throwable failure;

        Outcome(T value, Throwable failure) { this.value = value; this.failure = failure; }
    }
}
