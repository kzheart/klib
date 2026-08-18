package me.kzheart.klib.ui.prompt;

import me.kzheart.klib.KLogger;
import me.kzheart.klib.scope.Disposable;
import me.kzheart.klib.scope.Scope;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 作用域持有的聊天提示所使用的统一 Bukkit 监听器。 */
public final class BukkitChatPrompts implements Listener, Disposable {
    private final Scope owner;
    private final Plugin plugin;
    private final KLogger logger;
    private final Map<UUID, ActivePrompt<?>> active =
            new HashMap<UUID, ActivePrompt<?>>();
    private boolean disposed;

    private BukkitChatPrompts(Scope owner, Plugin plugin, KLogger logger) {
        this.owner = owner;
        this.plugin = plugin;
        this.logger = logger;
    }

    /** 安装一个提示与注册均归 {@code owner} 所有的监听器。 */
    public static BukkitChatPrompts install(Scope owner, Plugin plugin) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(plugin, "plugin");
        if (owner.isClosed()) {
            throw new IllegalStateException("cannot install chat prompts in a closed scope");
        }
        KLogger logger = owner.findCapability(KLogger.class)
                .orElseGet(() -> new KLogger(plugin.getLogger()));
        BukkitChatPrompts prompts = new BukkitChatPrompts(owner, plugin, logger);
        owner.install(prompts);
        try {
            plugin.getServer().getPluginManager().registerEvents(prompts, plugin);
        } catch (RuntimeException failure) {
            prompts.dispose();
            throw failure;
        }
        return prompts;
    }

    /** 启动或替换玩家当前的活动提示。 */
    public <T> PromptSession<T> start(Player player, PromptSpec<T> spec) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(spec, "spec");
        PromptSession<T> session;
        ActivePrompt<T> prompt;
        ActivePrompt<?> previous;
        synchronized (this) {
            ensureOpen();
            session = ChatPrompt.start(owner, spec.timeout(), spec.parser());
            prompt = new ActivePrompt<T>(player.getUniqueId(), spec, session);
            previous = active.put(player.getUniqueId(), prompt);
        }
        if (previous != null) {
            previous.superseded = true;
            previous.session.cancel();
        }
        session.completionSync().whenComplete((outcome, failure) -> {
            synchronized (BukkitChatPrompts.this) {
                active.remove(prompt.playerId, prompt);
            }
            if (failure != null) {
                if (!owner.isClosed()) {
                    logger.error("聊天输入结果无法切回主线程: player=" + prompt.playerId, failure);
                }
                return;
            }
            Player current = plugin.getServer().getPlayer(prompt.playerId);
            if (current == null || !current.isOnline()) {
                return;
            }
            if (outcome.status() == PromptStatus.CANCELLED && !prompt.superseded) {
                send(current, spec.cancelledMessage());
            } else if (outcome.status() == PromptStatus.TIMED_OUT) {
                send(current, spec.timeoutMessage());
            }
        });
        return session;
    }

    public synchronized boolean hasPrompt(UUID playerId) {
        return active.containsKey(Objects.requireNonNull(playerId, "playerId"));
    }

    public boolean cancel(UUID playerId) {
        ActivePrompt<?> prompt;
        synchronized (this) {
            prompt = active.get(Objects.requireNonNull(playerId, "playerId"));
        }
        return prompt != null && prompt.session.cancel();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onChat(AsyncPlayerChatEvent event) {
        ActivePrompt<?> prompt;
        synchronized (this) {
            if (disposed) {
                return;
            }
            prompt = active.get(event.getPlayer().getUniqueId());
        }
        if (prompt == null) {
            return;
        }
        event.setCancelled(true);
        String input = event.getMessage();
        if (prompt.spec.cancelKeyword().equalsIgnoreCase(input.trim())) {
            prompt.session.cancel();
            return;
        }
        if (!prompt.session.submit(input)) {
            dispatchMessage(prompt.playerId, prompt.spec.invalidMessage());
        }
    }

    @Override
    public void dispose() {
        List<ActivePrompt<?>> snapshot;
        synchronized (this) {
            if (disposed) {
                return;
            }
            disposed = true;
            snapshot = new ArrayList<ActivePrompt<?>>(active.values());
            active.clear();
        }
        HandlerList.unregisterAll(this);
        for (ActivePrompt<?> prompt : snapshot) {
            prompt.session.cancel();
        }
    }

    private void dispatchMessage(UUID playerId, String message) {
        if (message.isEmpty()) {
            return;
        }
        try {
            owner.after(me.kzheart.klib.scheduler.Ticks.of(0L), () -> {
                Player player = plugin.getServer().getPlayer(playerId);
                if (player != null && player.isOnline()) {
                    send(player, message);
                }
            });
        } catch (RuntimeException failure) {
            if (!owner.isClosed()) {
                logger.error("聊天输入反馈无法切回主线程: player=" + playerId, failure);
            }
        }
    }

    private static void send(Player player, String message) {
        if (!message.isEmpty()) {
            player.sendMessage(message);
        }
    }

    private synchronized void ensureOpen() {
        if (disposed || owner.isClosed()) {
            throw new IllegalStateException("chat prompt bridge is closed");
        }
    }

    private static final class ActivePrompt<T> {
        private final UUID playerId;
        private final PromptSpec<T> spec;
        private final PromptSession<T> session;
        private volatile boolean superseded;

        private ActivePrompt(UUID playerId, PromptSpec<T> spec, PromptSession<T> session) {
            this.playerId = playerId;
            this.spec = spec;
            this.session = session;
        }
    }
}
