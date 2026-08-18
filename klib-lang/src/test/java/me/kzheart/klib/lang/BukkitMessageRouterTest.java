package me.kzheart.klib.lang;

import org.bukkit.Server;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BukkitMessageRouterTest {
    @Test
    void routesChatActionBarAndTitleToTheirBukkitChannels() {
        PlayerState playerState = new PlayerState();
        Player player = playerState.player();
        ComponentState components = new ComponentState();
        BukkitMessageRouter router = new BukkitMessageRouter(server(new ArrayList<BossBarState>()), components);
        MessageRecipient recipient = MessageRecipient.of(player, false);
        RichText message = RichText.plain("hello");

        router.route(recipient, MessageRoute.CHAT, message);
        assertFalse(components.actionBar);
        assertEquals(message, components.message);

        router.route(recipient, MessageRoute.ACTION_BAR, message);
        assertTrue(components.actionBar);
        assertEquals(message, components.message);

        router.route(recipient, MessageRoute.TITLE, message);
        assertEquals("hello", playerState.title);
        assertEquals("", playerState.subtitle);
        router.dispose();
    }

    @Test
    void fallsBackToLegacyWhenComponentTransportIsUnavailable() {
        PlayerState playerState = new PlayerState();
        BukkitMessageRouter router = new BukkitMessageRouter(
                server(new ArrayList<BossBarState>()),
                (player, message, actionBar) -> false);

        router.route(
                MessageRecipient.of(playerState.player(), false),
                MessageRoute.CHAT,
                RichText.plain("fallback"));

        assertEquals("fallback", playerState.legacyMessage);
        router.dispose();
    }

    @Test
    void replacesAndReleasesBossBars() {
        List<BossBarState> bars = new ArrayList<BossBarState>();
        BukkitMessageRouter router = new BukkitMessageRouter(server(bars), new ComponentState());
        MessageRecipient recipient = MessageRecipient.of(new PlayerState().player(), false);

        router.route(recipient, MessageRoute.BOSS_BAR, RichText.plain("first"));
        router.route(recipient, MessageRoute.BOSS_BAR, RichText.plain("second"));

        assertEquals(2, bars.size());
        assertEquals("first", bars.get(0).title);
        assertEquals(1, bars.get(0).removeCount);
        assertEquals(1, bars.get(1).addCount);

        router.dispose();

        assertEquals(1, bars.get(1).removeCount);
        assertThrows(
                IllegalStateException.class,
                () -> router.route(recipient, MessageRoute.CHAT, RichText.plain("closed")));
    }

    @Test
    void consoleAlwaysReceivesPlainText() {
        ConsoleSender console = new ConsoleSender();
        BukkitMessageRouter router = new BukkitMessageRouter(server(new ArrayList<BossBarState>()));

        router.route(
                MessageRecipient.of(console, true),
                MessageRoute.ACTION_BAR,
                new RichText(Collections.singletonList(new RichTextSegment(
                        "plain",
                        MessageColor.RED,
                        false,
                        new TextAction(TextAction.Type.HOVER_TEXT, "tip"),
                        null))));

        assertEquals("plain", console.message);
        router.dispose();
    }

    private static Server server(List<BossBarState> bars) {
        return (Server) Proxy.newProxyInstance(
                BukkitMessageRouterTest.class.getClassLoader(),
                new Class<?>[]{Server.class},
                (proxy, method, arguments) -> {
                    if ("createBossBar".equals(method.getName())) {
                        BossBarState state = new BossBarState((String) arguments[0]);
                        bars.add(state);
                        return state.bossBar();
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (type == Boolean.TYPE) {
            return Boolean.FALSE;
        }
        if (type == Integer.TYPE) {
            return Integer.valueOf(0);
        }
        if (type == Long.TYPE) {
            return Long.valueOf(0L);
        }
        if (type == Double.TYPE) {
            return Double.valueOf(0.0D);
        }
        if (type == Float.TYPE) {
            return Float.valueOf(0.0F);
        }
        if (type == Short.TYPE) {
            return Short.valueOf((short) 0);
        }
        if (type == Byte.TYPE) {
            return Byte.valueOf((byte) 0);
        }
        if (type == Character.TYPE) {
            return Character.valueOf('\0');
        }
        return null;
    }

    private static final class ComponentState implements BukkitComponentSender {
        private RichText message;
        private boolean actionBar;

        @Override
        public boolean send(Player player, RichText sentMessage, boolean sentActionBar) {
            message = sentMessage;
            actionBar = sentActionBar;
            return true;
        }
    }

    private static final class PlayerState {
        private final UUID uniqueId = UUID.randomUUID();
        private String legacyMessage;
        private String title;
        private String subtitle;

        private Player player() {
            return (Player) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[]{Player.class},
                    (proxy, method, arguments) -> {
                        if ("getUniqueId".equals(method.getName())) {
                            return uniqueId;
                        }
                        if ("sendMessage".equals(method.getName())
                                && arguments != null
                                && arguments.length == 1
                                && arguments[0] instanceof String) {
                            legacyMessage = (String) arguments[0];
                            return null;
                        }
                        if ("sendTitle".equals(method.getName())) {
                            title = (String) arguments[0];
                            subtitle = (String) arguments[1];
                            return null;
                        }
                        return defaultValue(method.getReturnType());
                    });
        }
    }

    private static final class BossBarState {
        private final String title;
        private int addCount;
        private int removeCount;

        private BossBarState(String title) {
            this.title = title;
        }

        private BossBar bossBar() {
            return (BossBar) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[]{BossBar.class},
                    (proxy, method, arguments) -> {
                        if ("addPlayer".equals(method.getName())) {
                            addCount++;
                            return null;
                        }
                        if ("removeAll".equals(method.getName())) {
                            removeCount++;
                            return null;
                        }
                        return defaultValue(method.getReturnType());
                    });
        }
    }

    public static final class ConsoleSender {
        private String message;

        public void sendMessage(String value) {
            message = value;
        }
    }
}
