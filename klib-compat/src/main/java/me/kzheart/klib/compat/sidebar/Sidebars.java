/*
 * 差异更新流程改写自 TabooLib（https://github.com/TabooLib/taboolib）的
 * module/bukkit-nms/bukkit-nms-stable/src/main/kotlin/taboolib/module/nms/NMSScoreboard.kt、
 * NMSScoreboardImpl.kt 与 type/PlayerScoreboard.kt。
 *
 * Copyright (c) 2018 Bkm016
 * Licensed under the MIT License; the full license text is reproduced in THIRD_PARTY_NOTICES.md
 * and META-INF/LICENSE-TabooLib-Scoreboard.txt. Source revision: 0e3a911fc55624075b5c9abd4368cb5b063b022b.
 */
package me.kzheart.klib.compat.sidebar;

import me.kzheart.klib.compat.Capabilities;
import me.kzheart.klib.compat.CompatProvider;
import me.kzheart.klib.compat.CompatProviders;
import me.kzheart.klib.compat.SidebarBridge;
import me.kzheart.klib.scope.Disposable;
import me.kzheart.klib.scope.Scope;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按玩家发送的数据包侧边栏。
 *
 * <p>侧边栏只存在于目标玩家的客户端，不替换或修改玩家原有的 Bukkit {@code Scoreboard}。重复调用
 * {@link #show} 只发送与上一次内容不同的标题和行；玩家退出时自动丢弃状态，作用域关闭时向仍在线的
 * 玩家移除侧边栏。
 *
 * <p>所有方法都可以从任意线程调用，同一玩家的更新按调用顺序串行发送。
 */
public final class Sidebars implements Disposable {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SidebarBridge bridge;
    private final String objective;
    private final Map<UUID, PlayerSidebar> sidebars = new ConcurrentHashMap<UUID, PlayerSidebar>();
    private volatile boolean disposed;

    private Sidebars(SidebarBridge bridge, String objective) {
        this.bridge = bridge;
        this.objective = objective;
    }

    /**
     * 按当前服务端版本选择已打包的兼容实现并安装到作用域。
     *
     * @throws IllegalStateException 没有打包覆盖当前版本的 {@code klib-compat-v*} 模块，或该实现不支持侧边栏
     */
    public static Sidebars install(Scope scope) {
        Objects.requireNonNull(scope, "scope");
        CompatProvider provider = CompatProviders.resolveCurrent();
        SidebarBridge bridge = provider.capability(Capabilities.SIDEBAR).orElseThrow(
                () -> new IllegalStateException("Compatibility provider " + provider.id()
                        + " does not support packet sidebars on this server"));
        return install(scope, bridge);
    }

    /** 使用指定兼容实现安装到作用域，并在玩家退出时丢弃其侧边栏状态。 */
    public static Sidebars install(Scope scope, SidebarBridge bridge) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(bridge, "bridge");
        if (scope.isClosed()) {
            throw new IllegalStateException("cannot install sidebars in a closed scope");
        }
        Sidebars sidebars = scope.install(new Sidebars(bridge, newObjectiveName()));
        scope.on(PlayerQuitEvent.class, event -> sidebars.forget(event.getPlayer()));
        return sidebars;
    }

    /** 当前兼容实现可以完整显示的最大行数。 */
    public int maxLines() {
        return bridge.maxLines();
    }

    /** 显示或更新玩家的侧边栏；{@code lines} 自上而下排列。 */
    public void show(Player player, String title, String... lines) {
        Objects.requireNonNull(lines, "lines");
        show(player, title, Arrays.asList(lines));
    }

    /**
     * 显示或更新玩家的侧边栏；{@code lines} 自上而下排列，可以为空列表。
     *
     * <p>离线玩家的调用会被忽略。
     *
     * @throws IllegalArgumentException 行数超过 {@link #maxLines()}
     */
    public void show(Player player, String title, List<String> lines) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(title, "title");
        List<String> copy = copyLines(lines);
        ensureActive();
        if (!player.isOnline()) {
            return;
        }
        sidebars.compute(player.getUniqueId(), (id, current) -> {
            ensureActive();
            PlayerSidebar sidebar = current != null && current.belongsTo(player)
                    ? current
                    : new PlayerSidebar(bridge, objective, player);
            sidebar.show(title, copy);
            return sidebar;
        });
    }

    /** 移除玩家的侧边栏；之前未显示时返回 {@code false}。 */
    public boolean hide(Player player) {
        Objects.requireNonNull(player, "player");
        return hide(player.getUniqueId(), player);
    }

    public boolean isShown(Player player) {
        Objects.requireNonNull(player, "player");
        PlayerSidebar sidebar = sidebars.get(player.getUniqueId());
        return sidebar != null && sidebar.belongsTo(player);
    }

    /** 关闭后不再接受显示请求，并向仍在线的玩家移除侧边栏。 */
    @Override
    public void dispose() {
        disposed = true;
        RuntimeException failure = null;
        for (UUID id : new ArrayList<UUID>(sidebars.keySet())) {
            try {
                hide(id, null);
            } catch (RuntimeException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private boolean hide(UUID id, Player expected) {
        boolean[] shown = new boolean[1];
        RuntimeException[] failure = new RuntimeException[1];
        sidebars.computeIfPresent(id, (ignored, sidebar) -> {
            if (expected == null || sidebar.belongsTo(expected)) {
                shown[0] = true;
                try {
                    sidebar.hide();
                } catch (RuntimeException exception) {
                    failure[0] = exception;
                }
            }
            return null;
        });
        if (failure[0] != null) {
            throw failure[0];
        }
        return shown[0];
    }

    private void forget(Player player) {
        sidebars.remove(player.getUniqueId());
    }

    private void ensureActive() {
        if (disposed) {
            throw new IllegalStateException("sidebars have been disposed");
        }
    }

    private List<String> copyLines(List<String> lines) {
        Objects.requireNonNull(lines, "lines");
        List<String> copy = new ArrayList<String>(lines.size());
        for (String line : lines) {
            copy.add(Objects.requireNonNull(line, "line"));
        }
        int max = bridge.maxLines();
        if (copy.size() > max) {
            throw new IllegalArgumentException(
                    "sidebar supports at most " + max + " lines, got " + copy.size());
        }
        return Collections.unmodifiableList(copy);
    }

    private static String newObjectiveName() {
        return "ks" + String.format("%08x", RANDOM.nextInt());
    }
}
