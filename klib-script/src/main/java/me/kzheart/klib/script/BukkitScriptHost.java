package me.kzheart.klib.script;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

import me.kzheart.klib.scope.Disposable;
import me.kzheart.klib.scope.Scope;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.scheduler.BukkitTask;

/**
 * 由作用域持有、可重复安装到脚本上下文的 Bukkit 宿主服务。
 * <p>基础服务只创建一次；侧边栏和 JavaScript 均显式启用，不改变
 * {@link BukkitScriptServices#apply(ScriptContext.Builder, Plugin)} 的默认行为。
 * 构建、上下文安装、所有服务调用和释放操作都必须在服务器主线程执行。
 */
public final class BukkitScriptHost implements Disposable, AutoCloseable, Listener {
    private static final int MAX_LINES = 15;
    private final Plugin plugin;
    private final Scope scope;
    private final Supplier<? extends JavaScriptEvaluator> javascriptFactory;
    private final List<Consumer<ScriptContext.Builder>> services = new ArrayList<Consumer<ScriptContext.Builder>>();
    private final Map<UUID, Sidebar> sidebars = new HashMap<UUID, Sidebar>();
    private final Map<CompletableFuture<Object>, BukkitTask> pendingDelays = new HashMap<CompletableFuture<Object>, BukkitTask>();
    private final Object delayCleanupLock = new Object();
    private final Set<BukkitTask> delayCleanups = new HashSet<BukkitTask>();
    private JavaScriptEvaluator javascript;
    private volatile boolean closed;

    private BukkitScriptHost(Builder options) {
        checkThread();
        plugin = options.plugin;
        scope = options.scope;
        javascriptFactory = options.javascriptFactory;
        ScriptContext defaults = BukkitScriptServices.apply(ScriptContext.builder(), plugin,
                options.scoreboard ? this::scoreboard : options.scoreboardCallback, false).build();
        cache(defaults, MessageSink.class);
        cache(defaults, CommandSink.class);
        cache(defaults, ScriptSenderQuery.class);
        cache(defaults, PlayerQuery.class);
        DelayScheduler delayScheduler = this::delay;
        services.add(builder -> builder.service(DelayScheduler.class, delayScheduler));
        cache(defaults, ScriptLogger.class);
        cache(defaults, ScriptPlatform.class);
        cache(defaults, ScriptPropertyAccess.class);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        try {
            scope.install(this);
        } catch (RuntimeException failure) {
            dispose();
            throw failure;
        }
    }

    /** 创建显式可选能力的配置器；返回的宿主由 scope 自动释放。 */
    public static Builder builder(Plugin plugin, Scope scope) {
        return new Builder(plugin, scope);
    }

    /** 向一个新的脚本上下文安装共享服务，不重新发现或创建 JavaScript 引擎。 */
    public ScriptContext.Builder apply(ScriptContext.Builder builder) {
        checkActive();
        Objects.requireNonNull(builder, "builder");
        for (Consumer<ScriptContext.Builder> service : services) service.accept(builder);
        if (javascriptFactory != null) builder.service(JavaScriptEvaluator.class, this::evaluate);
        return builder;
    }

    private <T> void cache(ScriptContext defaults, Class<T> type) {
        T delegate = defaults.requireService(type);
        // 在公开接口边界检查线程和生命周期，包括 retained context 中已拿到的服务。
        T guarded = type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, arguments) -> invokeService(proxy, delegate, method, arguments)));
        services.add(builder -> builder.service(type, guarded));
    }

    private Object invokeService(Object proxy, Object delegate, Method method, Object[] arguments) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            if ("equals".equals(method.getName())) return Boolean.valueOf(proxy == arguments[0]);
            if ("hashCode".equals(method.getName())) return Integer.valueOf(System.identityHashCode(proxy));
            return "BukkitScriptHost service";
        }
        checkActive();
        try {
            return method.invoke(delegate, arguments);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    private Object evaluate(String source, Map<String, Object> bindings) {
        checkActive();
        if (javascript == null) {
            try {
                javascript = Objects.requireNonNull(javascriptFactory.get(),
                        "JavaScript evaluator factory returned null");
            } catch (RuntimeException | LinkageError failure) {
                throw new IllegalStateException("JavaScript evaluator could not start: " + failure.getMessage()
                        + ". Check the configured engine/provider and its runtime dependencies.", failure);
            }
        }
        return javascript.eval(source, bindings);
    }

    private CompletionStage<Object> delay(Duration duration) {
        checkActive();
        long ticks = Math.max(0L, Objects.requireNonNull(duration, "duration").toMillis() / 50L);
        CompletableFuture<Object> future = new CompletableFuture<Object>();
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            pendingDelays.remove(future);
            future.complete(null);
        }, ticks);
        pendingDelays.put(future, task);
        future.whenComplete((result, failure) -> {
            if (closed) return;
            // CompletableFuture.cancel/complete 可由调用者在其他线程发起。只有线程安全的
            // 调度投递在该线程执行，实际 task 清理与宿主状态变更统一回到主线程。
            if (Bukkit.isPrimaryThread()) cancelDelay(future);
            else scheduleDelayCleanup(future);
        });
        return future;
    }

    private void cancelDelay(CompletableFuture<Object> future) {
        checkThread();
        BukkitTask task = pendingDelays.remove(future);
        if (task != null) task.cancel();
    }

    private void scheduleDelayCleanup(CompletableFuture<Object> future) {
        synchronized (delayCleanupLock) {
            if (closed) return;
            final BukkitTask[] cleanup = new BukkitTask[1];
            cleanup[0] = Bukkit.getScheduler().runTask(plugin, () -> {
                synchronized (delayCleanupLock) { delayCleanups.remove(cleanup[0]); }
                if (!closed) cancelDelay(future);
            });
            delayCleanups.add(cleanup[0]);
        }
    }

    private void scoreboard(Player player, List<String> lines) {
        checkActive();
        if (lines == null || lines.isEmpty()) {
            remove(player);
            return;
        }
        if (lines.size() > MAX_LINES + 1) {
            throw new IllegalArgumentException("scoreboard supports a title and at most 15 sidebar lines");
        }
        for (String line : lines) Objects.requireNonNull(line, "scoreboard lines must not be null");
        Sidebar sidebar = sidebars.get(player.getUniqueId());
        if (sidebar == null || player.getScoreboard() != sidebar.board) {
            if (sidebar != null) {
                sidebars.remove(player.getUniqueId());
                discard(sidebar);
            }
            Scoreboard previous = player.getScoreboard();
            Scoreboard board = Objects.requireNonNull(Bukkit.getScoreboardManager(),
                    "Bukkit scoreboard manager is unavailable").getNewScoreboard();
            Objective objective = board.registerNewObjective("klib_script", "dummy");
            objective.setDisplaySlot(DisplaySlot.SIDEBAR);
            sidebar = new Sidebar(previous, board, objective);
            try {
                render(sidebar, lines);
                player.setScoreboard(board);
                sidebars.put(player.getUniqueId(), sidebar);
            } catch (RuntimeException failure) {
                discard(sidebar);
                throw failure;
            }
        } else {
            render(sidebar, lines);
        }
    }

    private static void render(Sidebar sidebar, List<String> lines) {
        sidebar.objective.setDisplayName(lines.get(0));
        for (String entry : new ArrayList<String>(sidebar.board.getEntries())) sidebar.board.resetScores(entry);
        for (Team team : new ArrayList<Team>(sidebar.board.getTeams())) team.unregister();
        for (int index = 1; index < lines.size(); index++) {
            // 行号使用不可见颜色码，不以显示文本作为 entry，保留重复行和空行。
            String entry = "\u00a7" + Integer.toHexString(index - 1) + "\u00a7r";
            Team team = sidebar.board.registerNewTeam("line" + index);
            team.setPrefix(lines.get(index));
            team.addEntry(entry);
            sidebar.objective.getScore(entry).setScore(lines.size() - index);
        }
    }

    private void remove(Player player) {
        Sidebar sidebar = sidebars.remove(player.getUniqueId());
        if (sidebar == null) return;
        try {
            if (player.getScoreboard() == sidebar.board) player.setScoreboard(sidebar.previous);
        } finally {
            discard(sidebar);
        }
    }

    private static void discard(Sidebar sidebar) {
        for (Team team : new ArrayList<Team>(sidebar.board.getTeams())) team.unregister();
        if (sidebar.objective.getScoreboard() != null) sidebar.objective.unregister();
    }

    /** 玩家离线时释放此宿主拥有的侧边栏。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        checkThread();
        if (!closed) remove(event.getPlayer());
    }

    /** 插件停用时立即释放，即使外层作用域还没有关闭。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDisable(PluginDisableEvent event) {
        if (event.getPlugin() == plugin) dispose();
    }

    private static void checkThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Bukkit script host services must run on the server main thread");
        }
    }

    private void checkActive() {
        checkThread();
        if (closed) throw new IllegalStateException("Bukkit script host services are closed");
    }

    /**
     * 恢复仍由本宿主显示的侧边栏并解除监听；不会覆盖其他插件后来设置的计分板。
     * JavaScript 工厂返回值若实现 Disposable 或 AutoCloseable，也会释放一次。
     */
    @Override public void dispose() {
        checkThread();
        List<BukkitTask> cleanups;
        synchronized (delayCleanupLock) {
            if (closed) return;
            closed = true;
            cleanups = new ArrayList<BukkitTask>(delayCleanups);
            delayCleanups.clear();
        }
        RuntimeException failure = null;
        try {
            for (BukkitTask cleanup : cleanups) {
                try {
                    cleanup.cancel();
                } catch (RuntimeException error) {
                    if (failure == null) failure = error;
                    else failure.addSuppressed(error);
                }
            }
            for (Map.Entry<CompletableFuture<Object>, BukkitTask> entry
                    : new ArrayList<Map.Entry<CompletableFuture<Object>, BukkitTask>>(pendingDelays.entrySet())) {
                pendingDelays.remove(entry.getKey());
                try {
                    entry.getValue().cancel();
                } catch (RuntimeException error) {
                    if (failure == null) failure = error;
                    else failure.addSuppressed(error);
                } finally {
                    entry.getKey().cancel(false);
                }
            }
            for (Map.Entry<UUID, Sidebar> entry : new ArrayList<Map.Entry<UUID, Sidebar>>(sidebars.entrySet())) {
                try {
                    Player player = Bukkit.getPlayer(entry.getKey());
                    if (player != null) remove(player);
                    else discard(entry.getValue());
                } catch (RuntimeException error) {
                    if (failure == null) failure = error;
                    else failure.addSuppressed(error);
                }
            }
            try {
                if (javascript instanceof Disposable) ((Disposable) javascript).dispose();
                else if (javascript instanceof AutoCloseable) ((AutoCloseable) javascript).close();
            } catch (Exception error) {
                RuntimeException wrapped = new IllegalStateException("Failed to close the JavaScript evaluator", error);
                if (failure == null) failure = wrapped;
                else failure.addSuppressed(wrapped);
            }
        } finally {
            sidebars.clear();
            pendingDelays.clear();
            services.clear();
            javascript = null;
            HandlerList.unregisterAll(this);
            scope.remove(this);
        }
        if (failure != null) throw failure;
    }

    /** 等价于 {@link #dispose()}。 */
    @Override public void close() { dispose(); }

    /** 宿主能力配置；未启用的能力不会被注册。 */
    public static final class Builder {
        private final Plugin plugin;
        private final Scope scope;
        private boolean scoreboard;
        private BiConsumer<Player, List<String>> scoreboardCallback;
        private Supplier<? extends JavaScriptEvaluator> javascriptFactory;

        private Builder(Plugin plugin, Scope scope) {
            this.plugin = Objects.requireNonNull(plugin, "plugin");
            this.scope = Objects.requireNonNull(scope, "scope");
        }

        /** 启用原生侧边栏：首行为标题，最多 15 行；null 或空列表移除。文本长度遵循服务器 API 限制。 */
        public Builder scoreboard(boolean enabled) {
            scoreboard = enabled;
            scoreboardCallback = null;
            return this;
        }

        /**
         * 使用插件自己的侧边栏服务，替换本宿主的原生侧边栏；null 行表示移除。
         * 此回调仍受主线程和生命周期检查，但其计分板与清理策略由插件持有。
         */
        public Builder scoreboard(BiConsumer<Player, List<String>> callback) {
            scoreboard = false;
            scoreboardCallback = Objects.requireNonNull(callback, "callback");
            return this;
        }

        /**
         * 在第一次 js 动作时调用工厂；创建的求值器由此宿主持有。
         * Klib 不捆绑引擎，插件可按自身 Java 版本提供 Nashorn 或其他实现。
         */
        public Builder javascript(Supplier<? extends JavaScriptEvaluator> factory) {
            javascriptFactory = Objects.requireNonNull(factory, "factory");
            return this;
        }

        /** 按 JSR-223 引擎名懒加载；缺少引擎时报错，不默默跳过 js 动作。 */
        public Builder javascriptEngine(String engineName) {
            javascriptFactory = ScriptJavaScriptEngines.named(engineName, plugin.getClass().getClassLoader());
            return this;
        }

        /** 在主线程创建宿主并注册到配置的作用域。 */
        public BukkitScriptHost build() { return new BukkitScriptHost(this); }
    }

    private static final class Sidebar {
        private final Scoreboard previous;
        private final Scoreboard board;
        private final Objective objective;

        private Sidebar(Scoreboard previous, Scoreboard board, Objective objective) {
            this.previous = previous;
            this.board = board;
            this.objective = objective;
        }
    }
}
