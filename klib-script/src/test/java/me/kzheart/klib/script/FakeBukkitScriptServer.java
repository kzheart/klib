package me.kzheart.klib.script;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventException;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;

/** 不依赖新版本 MockBukkit，覆盖 Java 8/旧版 Bukkit 公共接口。 */
final class FakeBukkitScriptServer implements AutoCloseable {
    private final Thread primary = Thread.currentThread();
    private final Map<UUID, Player> players = new HashMap<UUID, Player>();
    private final Map<Integer, Scheduled> tasks = new LinkedHashMap<Integer, Scheduled>();
    private int nextTask;
    private int cancelledTasks;
    private long tick;
    private final Server previous;
    final Plugin plugin = proxy(Plugin.class, (self, method, args) -> {
        if ("isEnabled".equals(method.getName())) return Boolean.TRUE;
        if ("getName".equals(method.getName())) return "script-test";
        if ("getLogger".equals(method.getName())) return Logger.getLogger("script-test");
        return null;
    });

    FakeBukkitScriptServer() throws ReflectiveOperationException {
        previous = Bukkit.getServer();
        PluginManager manager = proxy(PluginManager.class, (self, method, args) -> {
            if ("registerEvents".equals(method.getName())) register((Listener) args[0], (Plugin) args[1]);
            return null;
        });
        ScoreboardManager scoreboards = proxy(ScoreboardManager.class, (self, method, args) -> {
            if ("getNewScoreboard".equals(method.getName())) return board();
            return null;
        });
        BukkitScheduler scheduler = proxy(BukkitScheduler.class, (self, method, args) -> {
            if ("runTaskLater".equals(method.getName())) return schedule((Runnable) args[1], ((Long) args[2]).longValue());
            if ("runTask".equals(method.getName())) return schedule((Runnable) args[1], 0L);
            return null;
        });
        Server server = proxy(Server.class, (self, method, args) -> {
            switch (method.getName()) {
                case "isPrimaryThread": return Boolean.valueOf(Thread.currentThread() == primary);
                case "getPluginManager": return manager;
                case "getScoreboardManager": return scoreboards;
                case "getScheduler": return scheduler;
                case "getPlayer": return players.get(args[0]);
                case "getOnlinePlayers": return new ArrayList<Player>(players.values());
                case "getName": case "getVersion": case "getBukkitVersion": return "fake";
                case "getLogger": return Logger.getLogger("FakeBukkitScriptServer");
                default: return null;
            }
        });
        setServer(server);
    }

    int pendingTasks() { return tasks.size(); }
    int cancelledTasks() { return cancelledTasks; }

    void advance(long ticks) {
        for (long index = 0; index < ticks; index++) {
            tick++;
            for (Scheduled scheduled : new ArrayList<Scheduled>(tasks.values())) {
                if (scheduled.at <= tick && tasks.remove(scheduled.id) != null) scheduled.runnable.run();
            }
        }
    }

    private BukkitTask schedule(Runnable runnable, long ticks) {
        Scheduled scheduled = new Scheduled(++nextTask, tick + Math.max(1L, ticks), runnable);
        tasks.put(scheduled.id, scheduled);
        return proxy(BukkitTask.class, (self, method, args) -> {
            if ("getTaskId".equals(method.getName())) return Integer.valueOf(scheduled.id);
            if ("isSync".equals(method.getName())) return Boolean.TRUE;
            if ("cancel".equals(method.getName()) && tasks.remove(scheduled.id) != null) cancelledTasks++;
            return null;
        });
    }

    private static final class Scheduled {
        private final int id;
        private final long at;
        private final Runnable runnable;
        private Scheduled(int id, long at, Runnable runnable) {
            this.id = id;
            this.at = at;
            this.runnable = runnable;
        }
    }

    Player player() {
        UUID id = UUID.randomUUID();
        Scoreboard[] board = new Scoreboard[]{board()};
        Player player = proxy(Player.class, (self, method, args) -> {
            switch (method.getName()) {
                case "getUniqueId": return id;
                case "getName": return "Alex";
                case "isOnline": return Boolean.TRUE;
                case "getScoreboard": return board[0];
                case "setScoreboard": board[0] = (Scoreboard) args[0]; return null;
                default: return null;
            }
        });
        players.put(id, player);
        return player;
    }

    static Scoreboard board() { return new Board().value; }

    static List<String> rows(Scoreboard board) {
        Objective objective = board.getObjective(DisplaySlot.SIDEBAR);
        List<String> entries = new ArrayList<String>(board.getEntries());
        entries.sort((first, second) -> Integer.compare(objective.getScore(second).getScore(), objective.getScore(first).getScore()));
        List<String> rows = new ArrayList<String>();
        for (String entry : entries) rows.add(board.getEntryTeam(entry).getPrefix());
        return rows;
    }

    static void fire(Event event) throws Exception {
        for (RegisteredListener listener : event.getHandlers().getRegisteredListeners()) listener.callEvent(event);
    }

    private static void register(Listener listener, Plugin plugin) throws ReflectiveOperationException {
        for (Method method : listener.getClass().getMethods()) {
            EventHandler annotation = method.getAnnotation(EventHandler.class);
            if (annotation == null) continue;
            Class<?> event = method.getParameterTypes()[0];
            HandlerList handlers = (HandlerList) event.getMethod("getHandlerList").invoke(null);
            handlers.register(new RegisteredListener(listener, (ignored, value) -> {
                try { method.invoke(listener, value); }
                catch (ReflectiveOperationException failure) { throw new EventException(failure); }
            }, annotation.priority(), plugin, annotation.ignoreCancelled()));
        }
    }

    @Override public void close() throws ReflectiveOperationException {
        HandlerList.unregisterAll(plugin);
        setServer(previous);
    }

    private static void setServer(Server server) throws ReflectiveOperationException {
        Field field = Bukkit.class.getDeclaredField("server");
        field.setAccessible(true);
        field.set(null, server);
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (self, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                if ("equals".equals(method.getName())) return Boolean.valueOf(self == args[0]);
                if ("hashCode".equals(method.getName())) return Integer.valueOf(System.identityHashCode(self));
                return "Fake " + type.getSimpleName();
            }
            Object result = handler.invoke(self, method, args);
            if (result != null || !method.getReturnType().isPrimitive()) return result;
            if (method.getReturnType() == Boolean.TYPE) return Boolean.FALSE;
            if (method.getReturnType() == Integer.TYPE) return Integer.valueOf(0);
            if (method.getReturnType() == Long.TYPE) return Long.valueOf(0);
            if (method.getReturnType() == Double.TYPE) return Double.valueOf(0);
            if (method.getReturnType() == Float.TYPE) return Float.valueOf(0);
            return null;
        }));
    }

    private static final class Board {
        private final Map<String, Objective> objectives = new LinkedHashMap<String, Objective>();
        private final Map<String, Team> teams = new LinkedHashMap<String, Team>();
        private final Map<String, Integer> scores = new LinkedHashMap<String, Integer>();
        private Objective sidebar;
        private final Scoreboard value = proxy(Scoreboard.class, (self, method, args) -> {
            switch (method.getName()) {
                case "registerNewObjective": return objective((String) args[0]);
                case "getObjective": return args[0] instanceof DisplaySlot ? sidebar : objectives.get(args[0]);
                case "getObjectives": return new HashSet<Objective>(objectives.values());
                case "getEntries": return new HashSet<String>(scores.keySet());
                case "resetScores": scores.remove(args[0]); return null;
                case "registerNewTeam": return team((String) args[0]);
                case "getTeams": return new HashSet<Team>(teams.values());
                case "getEntryTeam":
                    for (Team team : teams.values()) if (team.hasEntry((String) args[0])) return team;
                    return null;
                default: return null;
            }
        });

        private Objective objective(String name) {
            String[] title = new String[]{name};
            Objective objective = proxy(Objective.class, (self, method, args) -> {
                switch (method.getName()) {
                    case "getName": return name;
                    case "setDisplayName": title[0] = (String) args[0]; return null;
                    case "getDisplayName": return title[0];
                    case "getScoreboard": return objectives.containsKey(name) ? value : null;
                    case "setDisplaySlot": sidebar = (Objective) self; return null;
                    case "unregister": objectives.remove(name); scores.clear(); sidebar = null; return null;
                    case "getScore":
                        String entry = (String) args[0];
                        return proxy(Score.class, (score, called, values) -> {
                            if ("setScore".equals(called.getName())) scores.put(entry, (Integer) values[0]);
                            if ("getScore".equals(called.getName())) return scores.get(entry);
                            return null;
                        });
                    default: return null;
                }
            });
            objectives.put(name, objective);
            return objective;
        }

        private Team team(String name) {
            String[] prefix = new String[]{""};
            Set<String> entries = new HashSet<String>();
            Team team = proxy(Team.class, (self, method, args) -> {
                switch (method.getName()) {
                    case "setPrefix": prefix[0] = (String) args[0]; return null;
                    case "getPrefix": return prefix[0];
                    case "addEntry": entries.add((String) args[0]); return null;
                    case "hasEntry": return Boolean.valueOf(entries.contains(args[0]));
                    case "unregister": teams.remove(name); return null;
                    default: return null;
                }
            });
            teams.put(name, team);
            return team;
        }
    }
}
