package me.kzheart.klib.compat.v1_12;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 用独立的 NMS 形状验证布局，不需要启动 Bukkit 或加载某一服务端版本。 */
class V1_12SidebarLayoutTest {
    @Test
    void legacyLayoutOwnsBoundedTextAndScoreEntries() throws Exception {
        Object layout = layout("LegacyScoreboard",
                new Class<?>[]{Class.class, Class.class, Class.class, Class.class},
                LegacyObjective.class, LegacyTeam.class, LegacyScorePacket.class, ScoreEntry.class);
        String title = "0123456789abcdef0123456789abcde§a";
        LegacyObjective objective = (LegacyObjective) call(layout, "objective", "obj", title);
        assertEquals("0123456789abcdef0123456789abcde", objective.title);
        assertSame(MapCriteria.DUMMY, objective.criteria);

        LegacyTeam team = (LegacyTeam) call(layout, "team", "line", "§aThis is a long line", "owner");
        assertEquals("§aThis is a long", team.prefix);
        assertEquals("§a line", team.suffix);
        assertEquals(Collections.singletonList("owner"), team.players);
        assertTrue(((LegacyTeam) call(layout, "team", "line", "new", null)).players.isEmpty());

        LegacyScorePacket change = (LegacyScorePacket) call(layout, "changeScore", "obj", "owner", 14);
        assertEquals("obj", change.objective.name);
        assertEquals("", change.objective.title);
        assertEquals("owner", change.owner);
        assertEquals(14, change.value);
        LegacyScorePacket remove = (LegacyScorePacket) call(layout, "removeScore", "obj", "owner");
        assertEquals("obj", remove.objective.name);
        assertEquals("owner", remove.owner);
        assertTrue(remove.remove);
    }

    @Test
    void componentLayoutProbesPrefixAndKeepsUnboundedText() throws Exception {
        Object layout = layout("ComponentScoreboard",
                new Class<?>[]{Class.class, Class.class, Constructor.class, Method.class},
                ComponentTeam.class, ActionScorePacket.class,
                ComponentObjective.class.getConstructors()[0], ChatComponents.class.getMethod("fromString", String.class));
        String title = "0123456789abcdef0123456789abcdef and more";
        ComponentObjective objective = (ComponentObjective) call(layout, "objective", "obj", title);
        assertEquals(title, objective.title.getString());
        assertSame(LookupCriteria.DUMMY, objective.criteria);
        assertEquals(Render.INTEGER, objective.render);
        assertEquals(" ", ((ComponentObjective) call(layout, "objective", "obj", "")).title.getString());

        ComponentTeam team = (ComponentTeam) call(layout, "team", "line", title, "owner");
        assertEquals(title + "N", team.format(new Component("N")).getString());
        assertEquals("", team.display.getString());
        assertEquals("", team.suffix.getString());
        assertEquals(Collections.singletonList("owner"), team.players);

        ActionScorePacket change = (ActionScorePacket) call(layout, "changeScore", "obj", "owner", 9);
        assertEquals(Action.CHANGE, change.action);
        assertEquals(9, change.value);
        assertEquals("obj", change.objective);
        assertEquals("owner", change.owner);
        ActionScorePacket remove = (ActionScorePacket) call(layout, "removeScore", "obj", "owner");
        assertEquals(Action.REMOVE, remove.action);
        assertEquals(0, remove.value);
    }

    @Test
    void teamPacketsPreserveModesForConstructorsAndFactories() throws Exception {
        LegacyTeam legacyTeam = new LegacyTeam(new Board(), "legacy");
        Object legacy = teamPackets(LegacyTeamPacket.class, LegacyTeam.class);
        assertEquals(0, ((LegacyTeamPacket) call(legacy, "change", legacyTeam, true)).mode);
        assertEquals(2, ((LegacyTeamPacket) call(legacy, "change", legacyTeam, false)).mode);
        assertEquals(1, ((LegacyTeamPacket) call(legacy, "remove", legacyTeam)).mode);

        ComponentTeam modernTeam = new ComponentTeam(new Board(), "modern");
        Object modern = teamPackets(FactoryTeamPacket.class, ComponentTeam.class);
        assertEquals(0, ((FactoryTeamPacket) call(modern, "change", modernTeam, true)).mode);
        assertEquals(2, ((FactoryTeamPacket) call(modern, "change", modernTeam, false)).mode);
        assertEquals(1, ((FactoryTeamPacket) call(modern, "remove", modernTeam)).mode);
    }

    private static Object layout(String name, Class<?>[] types, Object... arguments) throws Exception {
        Class<?> type = Class.forName(V1_12SidebarBridge.class.getName() + "$" + name);
        Constructor<?> constructor = type.getDeclaredConstructor(types);
        constructor.setAccessible(true);
        return constructor.newInstance(arguments);
    }

    private static Object teamPackets(Class<?> packet, Class<?> team) throws Exception {
        Class<?> type = Class.forName(V1_12SidebarBridge.class.getName() + "$TeamPackets");
        Field field = type.getDeclaredField("Companion");
        field.setAccessible(true);
        return call(field.get(null), "resolve", packet, team);
    }

    private static Object call(Object target, String name, Object... arguments) throws Exception {
        for (Method method : target.getClass().getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == arguments.length) {
                method.setAccessible(true);
                return method.invoke(target, arguments);
            }
        }
        throw new NoSuchMethodException(name);
    }

    public static final class Board { public Board() {} }

    public static final class MapCriteria {
        public static final MapCriteria DUMMY = new MapCriteria();
        public static final Map<String, MapCriteria> VALUES = Collections.singletonMap("dummy", DUMMY);
    }

    public static final class LookupCriteria {
        public static final LookupCriteria DUMMY = new LookupCriteria();
        public static Optional<LookupCriteria> byName(String name) {
            return "dummy".equals(name) ? Optional.of(DUMMY) : Optional.empty();
        }
    }

    public static final class LegacyObjective {
        final String name;
        final MapCriteria criteria;
        String title;
        public LegacyObjective(Board board, String name, MapCriteria criteria) {
            this.name = name;
            this.criteria = criteria;
        }
        public void setDisplayName(String title) { this.title = title; }
    }

    public static final class LegacyTeam {
        final Collection<String> players = new ArrayList<String>();
        String prefix;
        String suffix;
        public LegacyTeam(Board board, String name) {}
        public void setPrefix(String prefix) { this.prefix = prefix; }
        public void setSuffix(String suffix) { this.suffix = suffix; }
        public Collection<String> getPlayers() { return players; }
    }

    public static final class ScoreEntry {
        final LegacyObjective objective;
        final String owner;
        int value;
        public ScoreEntry(Board board, LegacyObjective objective, String owner) {
            this.objective = objective;
            this.owner = owner;
        }
        public void setScore(int value) { this.value = value; }
    }

    public static final class LegacyScorePacket {
        final LegacyObjective objective;
        final String owner;
        final int value;
        final boolean remove;
        public LegacyScorePacket(ScoreEntry entry) {
            objective = entry.objective;
            owner = entry.owner;
            value = entry.value;
            remove = false;
        }
        public LegacyScorePacket(String owner, LegacyObjective objective) {
            this.objective = objective;
            this.owner = owner;
            value = 0;
            remove = true;
        }
    }

    public enum Render { INTEGER }
    public enum Action { CHANGE, REMOVE }

    public static final class Component {
        private final String text;
        public Component(String text) { this.text = text; }
        public String getString() { return text; }
    }

    public static final class ChatComponents {
        public static Component[] fromString(String text) {
            return text.isEmpty() ? new Component[0] : new Component[]{new Component(text)};
        }
    }

    public static final class ComponentObjective {
        final Component title;
        final LookupCriteria criteria;
        final Render render;
        public ComponentObjective(Board board, String name, LookupCriteria criteria, Component title, Render render) {
            this.title = title;
            this.criteria = criteria;
            this.render = render;
        }
    }

    public static final class ComponentTeam {
        final Collection<String> players = new ArrayList<String>();
        Component prefix = new Component("");
        Component suffix = new Component("");
        Component display = new Component("");
        public ComponentTeam(Board board, String name) {}
        public void a(Component value) { suffix = value; }
        public void b(Component value) { prefix = value; }
        public void c(Component value) { display = value; }
        public Component format(Component name) {
            return new Component(prefix.getString() + name.getString() + suffix.getString());
        }
        public Collection<String> getPlayers() { return players; }
    }

    public static final class ActionScorePacket {
        final Action action;
        final String objective;
        final String owner;
        final int value;
        public ActionScorePacket(Action action, String objective, String owner, int value) {
            this.action = action;
            this.objective = objective;
            this.owner = owner;
            this.value = value;
        }
    }

    public static final class LegacyTeamPacket {
        final int mode;
        public LegacyTeamPacket(LegacyTeam team, int mode) { this.mode = mode; }
    }

    public static final class FactoryTeamPacket {
        final int mode;
        private FactoryTeamPacket(int mode) { this.mode = mode; }
        public static FactoryTeamPacket a(ComponentTeam team, boolean add) { return new FactoryTeamPacket(add ? 0 : 2); }
        public static FactoryTeamPacket b(ComponentTeam team) { return new FactoryTeamPacket(1); }
    }
}
