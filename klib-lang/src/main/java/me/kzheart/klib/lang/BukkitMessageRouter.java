package me.kzheart.klib.lang;

import me.kzheart.klib.scheduler.KScheduler;
import me.kzheart.klib.scheduler.Ticks;
import me.kzheart.klib.scope.Disposable;
import org.bukkit.Server;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class BukkitMessageRouter implements MessageRouter, Disposable {
    /** 标题的默认时间参数（刻）与 Boss 栏显示时长。 */
    private static final int TITLE_FADE_IN = 10;
    private static final int TITLE_STAY = 60;
    private static final int TITLE_FADE_OUT = 10;
    private static final Ticks DEFAULT_BOSS_BAR_DURATION = Ticks.of(100L);

    private final Object lifecycleLock = new Object();
    private final Server server;
    private final BukkitComponentSender components;
    private final KScheduler scheduler;
    private final Ticks bossBarDuration;
    private final Map<UUID, BossBarEntry> bossBars = new LinkedHashMap<UUID, BossBarEntry>();
    private boolean disposed;

    public BukkitMessageRouter(Server server) {
        this(server, ReflectionBukkitComponentSender.INSTANCE, null, DEFAULT_BOSS_BAR_DURATION);
    }

    public BukkitMessageRouter(Server server, KScheduler scheduler) {
        this(server, ReflectionBukkitComponentSender.INSTANCE, scheduler, DEFAULT_BOSS_BAR_DURATION);
    }

    BukkitMessageRouter(Server server, BukkitComponentSender components) {
        this(server, components, null, DEFAULT_BOSS_BAR_DURATION);
    }

    BukkitMessageRouter(
            Server server,
            BukkitComponentSender components,
            KScheduler scheduler,
            Ticks bossBarDuration
    ) {
        this.server = Objects.requireNonNull(server, "server");
        this.components = Objects.requireNonNull(components, "components");
        this.scheduler = scheduler;
        this.bossBarDuration = Objects.requireNonNull(bossBarDuration, "bossBarDuration");
    }

    @Override
    public void route(MessageRecipient recipient, RichText message) {
        route(recipient, MessageRoute.CHAT, message);
    }

    @Override
    public void route(MessageRecipient recipient, MessageRoute route, RichText message) {
        Objects.requireNonNull(recipient, "recipient");
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(message, "message");
        synchronized (lifecycleLock) {
            ensureOpen();
        }
        releaseOfflineBossBars();
        if (recipient.isConsole() || !(recipient.handle() instanceof Player)) {
            recipient.sendLegacy(message.plainText());
            return;
        }
        Player player = (Player) recipient.handle();
        if (route == MessageRoute.CHAT) {
            sendComponentOrLegacy(player, message, false);
        } else if (route == MessageRoute.ACTION_BAR) {
            sendComponentOrLegacy(player, message, true);
        } else if (route == MessageRoute.TITLE) {
            sendTitle(player, message);
        } else if (route == MessageRoute.BOSS_BAR) {
            sendBossBar(player, message);
        }
    }

    @Override
    public void dispose() {
        List<BossBarEntry> entries;
        synchronized (lifecycleLock) {
            if (disposed) {
                return;
            }
            disposed = true;
            entries = new ArrayList<BossBarEntry>(bossBars.values());
            bossBars.clear();
        }
        for (BossBarEntry entry : entries) {
            entry.bar.removeAll();
        }
    }

    private void sendComponentOrLegacy(Player player, RichText message, boolean actionBar) {
        if (!components.send(player, message, actionBar)) {
            player.sendMessage(message.legacyText());
        }
    }

    /** 支持 {@code title:主标题|副标题} 语法；第一个“|”之后的文本为副标题。 */
    private static void sendTitle(Player player, RichText message) {
        String legacy = message.legacyText();
        int separator = legacy.indexOf('|');
        String title = separator >= 0 ? legacy.substring(0, separator) : legacy;
        String subtitle = separator >= 0 ? legacy.substring(separator + 1) : "";
        player.sendTitle(title, subtitle, TITLE_FADE_IN, TITLE_STAY, TITLE_FADE_OUT);
    }

    private void sendBossBar(Player player, RichText message) {
        BossBar replacement = server.createBossBar(
                message.legacyText(),
                BarColor.WHITE,
                BarStyle.SOLID);
        replacement.addPlayer(player);
        BossBarEntry entry = new BossBarEntry(player, replacement);
        BossBarEntry previous;
        synchronized (lifecycleLock) {
            if (disposed) {
                replacement.removeAll();
                throw new IllegalStateException("Bukkit message router is closed");
            }
            previous = bossBars.put(player.getUniqueId(), entry);
        }
        if (previous != null) {
            previous.bar.removeAll();
        }
        if (scheduler != null) {
            scheduler.after(bossBarDuration, () -> expireBossBar(player.getUniqueId(), entry));
        }
    }

    /** 移除定时 Boss 栏，但若它已被更新的 Boss 栏替换则不移除。 */
    private void expireBossBar(UUID playerId, BossBarEntry entry) {
        synchronized (lifecycleLock) {
            if (disposed || bossBars.get(playerId) != entry) {
                return;
            }
            bossBars.remove(playerId);
        }
        entry.bar.removeAll();
    }

    /** 延迟移除属于已离开服务器玩家的 Boss 栏。 */
    private void releaseOfflineBossBars() {
        List<BossBarEntry> released = null;
        synchronized (lifecycleLock) {
            if (bossBars.isEmpty()) {
                return;
            }
            Iterator<BossBarEntry> iterator = bossBars.values().iterator();
            while (iterator.hasNext()) {
                BossBarEntry entry = iterator.next();
                if (!entry.player.isOnline()) {
                    iterator.remove();
                    if (released == null) {
                        released = new ArrayList<BossBarEntry>();
                    }
                    released.add(entry);
                }
            }
        }
        if (released != null) {
            for (BossBarEntry entry : released) {
                entry.bar.removeAll();
            }
        }
    }

    private void ensureOpen() {
        if (disposed) {
            throw new IllegalStateException("Bukkit message router is closed");
        }
    }

    private static final class BossBarEntry {
        private final Player player;
        private final BossBar bar;

        private BossBarEntry(Player player, BossBar bar) {
            this.player = player;
            this.bar = bar;
        }
    }
}
