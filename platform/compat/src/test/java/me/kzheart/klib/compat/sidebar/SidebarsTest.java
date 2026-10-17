package me.kzheart.klib.compat.sidebar;

import me.kzheart.klib.KLogger;
import me.kzheart.klib.compat.SidebarBridge;
import me.kzheart.klib.event.KEventDispatcher;
import me.kzheart.klib.scope.ScopeImpl;
import org.bukkit.entity.Player;
import org.bukkit.event.EventException;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SidebarsTest {
    private final AtomicReference<Listener> listener = new AtomicReference<Listener>();
    private final AtomicReference<EventExecutor> executor = new AtomicReference<EventExecutor>();
    private final RecordingBridge bridge = new RecordingBridge();
    private ScopeImpl scope;
    private Sidebars sidebars;

    @BeforeEach
    void install() {
        PluginManager manager = (PluginManager) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{PluginManager.class}, (target, method, arguments) -> {
                    if ("registerEvent".equals(method.getName())) {
                        listener.set((Listener) arguments[1]);
                        executor.set((EventExecutor) arguments[3]);
                    }
                    return null;
                });
        scope = new ScopeImpl("sidebars");
        scope.registerCapability(KEventDispatcher.class, scope.install(new KEventDispatcher(
                proxy(Plugin.class), manager, new KLogger(Logger.getLogger("test")))));
        sidebars = Sidebars.install(scope, bridge);
    }

    @Test
    void firstShowCreatesObjectiveAndLinesFromTheBottom() {
        TestPlayer player = new TestPlayer();

        sidebars.show(player.proxy, "TEST", "123", "456");

        assertEquals(Arrays.asList("create TEST", "add 0 456", "add 1 123"), bridge.calls());
        assertTrue(sidebars.isShown(player.proxy));
        assertTrue(bridge.objectives.iterator().next().length() <= 16);
    }

    @Test
    void repeatedShowOnlySendsDifferences() {
        TestPlayer player = new TestPlayer();
        sidebars.show(player.proxy, "TEST", "123", "456");
        bridge.clear();

        sidebars.show(player.proxy, "TEST", "123", "456");
        assertEquals(Collections.emptyList(), bridge.calls());

        sidebars.show(player.proxy, "TEST", "123");
        assertEquals(Arrays.asList("removeLine 1", "update 0 123"), bridge.calls());
        bridge.clear();

        sidebars.show(player.proxy, "UPDATED", "abc", "def", "ghi");
        assertEquals(Arrays.asList("title UPDATED", "update 0 ghi", "add 1 def", "add 2 abc"),
                bridge.calls());
        bridge.clear();

        sidebars.show(player.proxy, "UPDATED", "top", "abc", "def", "ghi");
        assertEquals(Collections.singletonList("add 3 top"), bridge.calls());
        bridge.clear();

        sidebars.show(player.proxy, "UPDATED", "abc", "changed", "ghi");
        assertEquals(Arrays.asList("removeLine 3", "update 1 changed"), bridge.calls());
    }

    @Test
    void emptyLinesKeepTheTitleAndRemoveEveryLine() {
        TestPlayer player = new TestPlayer();
        sidebars.show(player.proxy, "TEST", "a", "b");
        bridge.clear();

        sidebars.show(player.proxy, "TEST", Collections.<String>emptyList());

        assertEquals(Arrays.asList("removeLine 1", "removeLine 0"), bridge.calls());
        assertTrue(sidebars.isShown(player.proxy));
    }

    @Test
    void rejectsTooManyLinesAndNullTextWithoutSendingPackets() {
        TestPlayer player = new TestPlayer();
        List<String> lines = new ArrayList<String>();
        for (int index = 0; index <= bridge.maxLines(); index++) {
            lines.add("line " + index);
        }

        assertThrows(IllegalArgumentException.class, () -> sidebars.show(player.proxy, "TEST", lines));
        assertThrows(NullPointerException.class,
                () -> sidebars.show(player.proxy, "TEST", Arrays.asList("ok", null)));
        assertThrows(NullPointerException.class, () -> sidebars.show(player.proxy, null, "line"));
        assertEquals(Collections.emptyList(), bridge.calls());
        assertFalse(sidebars.isShown(player.proxy));
    }

    @Test
    void callerListChangesDoNotLeakIntoState() {
        TestPlayer player = new TestPlayer();
        List<String> lines = new ArrayList<String>(Arrays.asList("a", "b"));
        sidebars.show(player.proxy, "TEST", lines);
        lines.set(0, "mutated");
        bridge.clear();

        sidebars.show(player.proxy, "TEST", "a", "b");

        assertEquals(Collections.emptyList(), bridge.calls());
    }

    @Test
    void hideRemovesObjectiveAndNextShowRecreatesIt() {
        TestPlayer player = new TestPlayer();
        sidebars.show(player.proxy, "TEST", "a", "b", "c");
        bridge.clear();

        assertTrue(sidebars.hide(player.proxy));
        assertEquals(Collections.singletonList("remove 3"), bridge.calls());
        assertFalse(sidebars.isShown(player.proxy));
        assertFalse(sidebars.hide(player.proxy));
        bridge.clear();

        sidebars.show(player.proxy, "TEST", "a");
        assertEquals(Arrays.asList("create TEST", "add 0 a"), bridge.calls());
    }

    @Test
    void offlinePlayersAreIgnored() {
        TestPlayer player = new TestPlayer();
        player.online.set(false);

        sidebars.show(player.proxy, "TEST", "a");

        assertEquals(Collections.emptyList(), bridge.calls());
        assertFalse(sidebars.isShown(player.proxy));
    }

    @Test
    void quitDiscardsStateWithoutSendingPackets() throws EventException {
        TestPlayer leaving = new TestPlayer();
        TestPlayer staying = new TestPlayer();
        sidebars.show(leaving.proxy, "TEST", "a");
        sidebars.show(staying.proxy, "TEST", "b");
        bridge.clear();

        executor.get().execute(listener.get(), new PlayerQuitEvent(leaving.proxy, "bye"));

        assertEquals(Collections.emptyList(), bridge.calls());
        assertFalse(sidebars.isShown(leaving.proxy));
        assertTrue(sidebars.isShown(staying.proxy));

        TestPlayer rejoined = new TestPlayer(leaving.id);
        sidebars.show(rejoined.proxy, "TEST", "a");
        assertEquals(Arrays.asList("create TEST", "add 0 a"), bridge.calls());
    }

    @Test
    void newLoginWithSameIdStartsFromAFreshClientState() {
        TestPlayer first = new TestPlayer();
        sidebars.show(first.proxy, "TEST", "a");
        bridge.clear();
        TestPlayer second = new TestPlayer(first.id);

        assertFalse(sidebars.isShown(second.proxy));
        assertFalse(sidebars.hide(second.proxy));
        sidebars.show(second.proxy, "TEST", "a");

        assertEquals(Arrays.asList("create TEST", "add 0 a"), bridge.calls());
    }

    @Test
    void closingTheScopeRemovesSidebarsFromOnlinePlayersAndRejectsLaterShows() {
        TestPlayer online = new TestPlayer();
        TestPlayer offline = new TestPlayer();
        sidebars.show(online.proxy, "TEST", "a", "b");
        sidebars.show(offline.proxy, "TEST", "c");
        offline.online.set(false);
        bridge.clear();

        scope.close();

        assertEquals(Collections.singletonList("remove 2"), bridge.calls());
        assertFalse(sidebars.isShown(online.proxy));
        assertThrows(IllegalStateException.class, () -> sidebars.show(online.proxy, "TEST", "a"));
    }

    @Test
    void installRejectsClosedScopeAndEachInstanceUsesItsOwnObjective() {
        TestPlayer player = new TestPlayer();
        Sidebars other = Sidebars.install(scope, bridge);
        sidebars.show(player.proxy, "A", "a");
        other.show(player.proxy, "B", "b");

        assertEquals(2, bridge.objectives.size());
        scope.close();
        assertThrows(IllegalStateException.class, () -> Sidebars.install(scope, bridge));
    }

    @Test
    void maxLinesComesFromTheBridge() {
        assertEquals(15, sidebars.maxLines());
    }

    private static <T> T proxy(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (target, method, arguments) -> null));
    }

    private static final class TestPlayer {
        private final UUID id;
        private final AtomicBoolean online = new AtomicBoolean(true);
        private final Player proxy;

        TestPlayer() {
            this(UUID.randomUUID());
        }

        TestPlayer(UUID id) {
            this.id = id;
            proxy = (Player) Proxy.newProxyInstance(SidebarsTest.class.getClassLoader(),
                    new Class<?>[]{Player.class}, (target, method, arguments) -> {
                        switch (method.getName()) {
                            case "getUniqueId":
                                return this.id;
                            case "isOnline":
                                return online.get();
                            case "hashCode":
                                return System.identityHashCode(target);
                            case "equals":
                                return target == arguments[0];
                            default:
                                return null;
                        }
                    });
        }
    }

    private static final class RecordingBridge implements SidebarBridge {
        private final List<String> calls = new ArrayList<String>();
        private final Set<String> objectives = new HashSet<String>();

        synchronized List<String> calls() {
            return new ArrayList<String>(calls);
        }

        synchronized void clear() {
            calls.clear();
        }

        private synchronized void record(String objective, String call) {
            objectives.add(objective);
            calls.add(call);
        }

        @Override
        public int maxLines() {
            return 15;
        }

        @Override
        public void create(Player player, String objective, String title) {
            record(objective, "create " + title);
        }

        @Override
        public void title(Player player, String objective, String title) {
            record(objective, "title " + title);
        }

        @Override
        public void addLine(Player player, String objective, int index, String text) {
            record(objective, "add " + index + " " + text);
        }

        @Override
        public void updateLine(Player player, String objective, int index, String text) {
            record(objective, "update " + index + " " + text);
        }

        @Override
        public void removeLine(Player player, String objective, int index) {
            record(objective, "removeLine " + index);
        }

        @Override
        public void remove(Player player, String objective, int lineCount) {
            record(objective, "remove " + lineCount);
        }
    }
}
