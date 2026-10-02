/*
 * 数据包侧边栏实现，改写自 TabooLib（https://github.com/TabooLib/taboolib）的
 * module/bukkit-nms/bukkit-nms-stable/src/main/kotlin/taboolib/module/nms/NMSScoreboardImpl.kt：
 * 沿用其“每行一个队伍、前缀承载文本”的做法与 1.12 前后缀拆分规则，用 Java 反射代替 TabooLib 的 NMS
 * 代理与重映射。
 *
 * Copyright (c) 2018 Bkm016
 * Licensed under the MIT License; the full license text is reproduced in THIRD_PARTY_NOTICES.md
 * and META-INF/LICENSE-TabooLib-Scoreboard.txt. Source revision: 0e3a911fc55624075b5c9abd4368cb5b063b022b.
 */
package me.kzheart.klib.compat.v1_12;

import me.kzheart.klib.compat.SidebarBridge;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Minecraft 1.12.2 至 1.19.x 的侧边栏数据包实现。
 *
 * <p>这些版本的分数没有自定义显示文本：每行是一个只含不可见持有者的队伍，文本放在队伍前缀中，
 * 右侧显示客户端默认的红色分数。1.12 的文本是旧式字符串，前缀与后缀各限 16 个字符、标题限 32 个
 * 字符，超出部分按 TabooLib 的规则拆到后缀并截断；1.13 起使用聊天组件，没有这些长度限制。
 * 1.17 起数据包位于 Mojang 包名下，成员按签名查找。
 */
final class V1_12SidebarBridge implements SidebarBridge {
    private static final int MAX_LINES = 15;
    private static final int METHOD_ADD = 0;
    private static final int METHOD_REMOVE = 1;
    private static final int METHOD_CHANGE = 2;
    private static final int LEGACY_TEXT = 16;
    private static final int LEGACY_TITLE = 32;
    private static final String GAME_PACKETS = "net.minecraft.network.protocol.game.";
    private static final String SCORES = "net.minecraft.world.scores.";

    private volatile Packets packets;

    @Override
    public int maxLines() {
        return MAX_LINES;
    }

    @Override
    public void create(Player player, String objective, String title) {
        Packets resolved = packets(player);
        Object handle = resolved.objective(objective, title);
        resolved.send(player, resolved.setObjective(handle, METHOD_ADD));
        resolved.send(player, resolved.display(handle));
    }

    @Override
    public void title(Player player, String objective, String title) {
        Packets resolved = packets(player);
        resolved.send(player, resolved.setObjective(resolved.objective(objective, title), METHOD_CHANGE));
    }

    @Override
    public void addLine(Player player, String objective, int index, String text) {
        Packets resolved = packets(player);
        String owner = owner(objective, index);
        resolved.send(player, resolved.team(team(objective, index), text, owner, true));
        resolved.send(player, resolved.changeScore(objective, owner, index));
    }

    @Override
    public void updateLine(Player player, String objective, int index, String text) {
        Packets resolved = packets(player);
        resolved.send(player, resolved.team(team(objective, index), text, null, false));
    }

    @Override
    public void removeLine(Player player, String objective, int index) {
        Packets resolved = packets(player);
        resolved.send(player, resolved.removeScore(objective, owner(objective, index)));
        resolved.send(player, resolved.removeTeam(team(objective, index)));
    }

    @Override
    public void remove(Player player, String objective, int lineCount) {
        Packets resolved = packets(player);
        resolved.send(player, resolved.setObjective(resolved.objective(objective, ""), METHOD_REMOVE));
        for (int index = 0; index < lineCount; index++) {
            resolved.send(player, resolved.removeTeam(team(objective, index)));
        }
    }

    static String team(String objective, int index) {
        return objective + hex(index);
    }

    /**
     * 只由颜色代码组成并以 {@code §r} 结尾的持有者：它显示在前缀与后缀之间，不能改变后缀的格式，
     * 也不能超过 1.12 的 40 字符上限。
     */
    static String owner(String objective, int index) {
        String key = String.format(Locale.ROOT, "%08x", objective.hashCode()) + hex(index);
        StringBuilder owner = new StringBuilder(key.length() * 2 + 2);
        for (int i = 0; i < key.length(); i++) {
            owner.append(ChatColor.COLOR_CHAR).append(key.charAt(i));
        }
        return owner.append(ChatColor.COLOR_CHAR).append('r').toString();
    }

    /** 按 TabooLib 的规则把 1.12 的行文本拆为不超过 16 个字符的前缀与后缀。 */
    static String[] splitLegacy(String text) {
        if (text.length() <= LEGACY_TEXT) {
            return new String[]{text, ""};
        }
        String prefix = text.substring(0, LEGACY_TEXT);
        String suffix;
        if (prefix.charAt(LEGACY_TEXT - 1) == ChatColor.COLOR_CHAR) {
            prefix = prefix.substring(0, LEGACY_TEXT - 1);
            suffix = ChatColor.getLastColors(prefix) + text.substring(LEGACY_TEXT - 1);
        } else {
            suffix = ChatColor.getLastColors(prefix) + text.substring(LEGACY_TEXT);
        }
        return new String[]{prefix, truncateLegacy(suffix, LEGACY_TEXT)};
    }

    /** 截断时不留下孤立的 {@code §}。 */
    static String truncateLegacy(String text, int limit) {
        if (text.length() <= limit) {
            return text;
        }
        String cut = text.substring(0, limit);
        return cut.charAt(limit - 1) == ChatColor.COLOR_CHAR ? cut.substring(0, limit - 1) : cut;
    }

    private static String hex(int index) {
        return String.valueOf(Character.forDigit(index >> 4 & 0xf, 16))
                + Character.forDigit(index & 0xf, 16);
    }

    private Packets packets(Player player) {
        Packets current = packets;
        if (current == null) {
            synchronized (this) {
                current = packets;
                if (current == null) {
                    current = Packets.resolve(player);
                    packets = current;
                }
            }
        }
        return current;
    }

    private static final class Packets {
        private final boolean legacyText;
        private final Constructor<?> objective;
        private final Method objectiveTitle;
        private final Object scoreboard;
        private final Object criteria;
        private final Object renderType;
        private final Constructor<?> setObjective;
        private final Constructor<?> display;
        private final Constructor<?> score;
        private final Object[] scoreActions;
        private final Constructor<?> legacyScore;
        private final Constructor<?> legacyScoreEntry;
        private final Method legacyScoreValue;
        private final Constructor<?> legacyRemoveScore;
        private final Constructor<?> teamConstructor;
        private final Constructor<?> teamModeConstructor;
        private final Method teamFactory;
        private final Method removeTeamFactory;
        private final Method teamPlayers;
        private final Method teamPrefix;
        private final Method teamSuffix;
        private final Method text;
        private final Class<?> packetType;
        private volatile Connection connection;

        private Packets(ClassLoader loader) throws ReflectiveOperationException {
            String craft = Bukkit.getServer().getClass().getPackage().getName();
            String versioned = "net.minecraft.server." + craft.substring(craft.lastIndexOf('.') + 1) + ".";
            packetType = type(loader, versioned + "Packet", "net.minecraft.network.protocol.Packet");
            Class<?> objectiveType = type(loader, versioned + "ScoreboardObjective", SCORES + "ScoreboardObjective");
            Constructor<?> objectiveConstructor = optionalConstructor(objectiveType, 5);
            legacyText = objectiveConstructor == null;
            objective = legacyText ? constructor(objectiveType, 3) : objectiveConstructor;
            Class<?>[] parameters = objective.getParameterTypes();
            scoreboard = parameters[0].getConstructor().newInstance();
            criteria = dummyCriteria(parameters[2]);
            renderType = legacyText ? null : parameters[4].getEnumConstants()[0];
            objectiveTitle = legacyText ? objectiveType.getMethod("setDisplayName", String.class) : null;
            Class<?> componentType = legacyText ? String.class : parameters[3];
            text = legacyText ? null : Class.forName(craft + ".util.CraftChatMessage", true, loader)
                    .getMethod("fromString", String.class);
            setObjective = type(loader,
                    versioned + "PacketPlayOutScoreboardObjective",
                    GAME_PACKETS + "PacketPlayOutScoreboardObjective")
                    .getConstructor(objectiveType, int.class);
            display = type(loader,
                    versioned + "PacketPlayOutScoreboardDisplayObjective",
                    GAME_PACKETS + "PacketPlayOutScoreboardDisplayObjective")
                    .getConstructor(int.class, objectiveType);

            Class<?> scoreType = type(loader,
                    versioned + "PacketPlayOutScoreboardScore",
                    GAME_PACKETS + "PacketPlayOutScoreboardScore");
            if (legacyText) {
                Class<?> scoreValue = type(loader, versioned + "ScoreboardScore");
                score = null;
                scoreActions = null;
                legacyScore = scoreType.getConstructor(scoreValue);
                legacyScoreEntry = scoreValue.getConstructor(parameters[0], objectiveType, String.class);
                legacyScoreValue = scoreValue.getMethod("setScore", int.class);
                legacyRemoveScore = scoreType.getConstructor(String.class, objectiveType);
            } else {
                score = actionScoreConstructor(scoreType);
                scoreActions = score.getParameterTypes()[0].getEnumConstants();
                legacyScore = null;
                legacyScoreEntry = null;
                legacyScoreValue = null;
                legacyRemoveScore = null;
            }

            Class<?> teamPacket = type(loader,
                    versioned + "PacketPlayOutScoreboardTeam",
                    GAME_PACKETS + "PacketPlayOutScoreboardTeam");
            Class<?> teamType = type(loader, versioned + "ScoreboardTeam", SCORES + "ScoreboardTeam");
            teamConstructor = teamType.getConstructor(parameters[0], String.class);
            teamModeConstructor = optionalConstructor(teamPacket, teamType, int.class);
            Method addOrModify = null;
            Method remove = null;
            if (teamModeConstructor == null) {
                for (Method method : teamPacket.getMethods()) {
                    Class<?>[] types = method.getParameterTypes();
                    if (!Modifier.isStatic(method.getModifiers()) || method.getReturnType() != teamPacket
                            || types.length == 0 || types[0] != teamType) {
                        continue;
                    }
                    if (types.length == 2 && types[1] == boolean.class) {
                        addOrModify = method;
                    } else if (types.length == 1) {
                        remove = method;
                    }
                }
                if (addOrModify == null || remove == null) {
                    throw new NoSuchMethodException(teamPacket.getName() + " team packet factories");
                }
            }
            teamFactory = addOrModify;
            removeTeamFactory = remove;
            teamPlayers = playersMethod(teamType);
            teamPrefix = prefixSetter(teamType, componentType);
            teamSuffix = legacyText ? teamType.getMethod("setSuffix", String.class) : null;
        }

        static Packets resolve(Player player) {
            try {
                return new Packets(player.getClass().getClassLoader());
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                throw new IllegalStateException(
                        "Cannot resolve sidebar packets on " + Bukkit.getVersion(), failure);
            }
        }

        Object objective(String name, String title) {
            if (!legacyText) {
                return create(objective, scoreboard, name, criteria, component(title), renderType);
            }
            Object handle = create(objective, scoreboard, name, criteria);
            invoke(objectiveTitle, handle, truncateLegacy(title, LEGACY_TITLE));
            return handle;
        }

        Object setObjective(Object handle, int method) {
            return create(setObjective, handle, method);
        }

        Object display(Object handle) {
            return create(display, 1, handle);
        }

        Object changeScore(String objectiveName, String owner, int value) {
            if (!legacyText) {
                return create(score, scoreActions[0], objectiveName, owner, value);
            }
            Object handle = objective(objectiveName, "");
            Object entry = create(legacyScoreEntry, scoreboard, handle, owner);
            invoke(legacyScoreValue, entry, value);
            return create(legacyScore, entry);
        }

        Object removeScore(String objectiveName, String owner) {
            if (!legacyText) {
                return create(score, scoreActions[1], objectiveName, owner, 0);
            }
            return create(legacyRemoveScore, owner, objective(objectiveName, ""));
        }

        Object team(String name, String line, String owner, boolean add) {
            Object team = create(teamConstructor, scoreboard, name);
            if (legacyText) {
                String[] parts = splitLegacy(line);
                invoke(teamPrefix, team, parts[0]);
                invoke(teamSuffix, team, parts[1]);
            } else {
                invoke(teamPrefix, team, component(line));
            }
            if (owner != null) {
                @SuppressWarnings("unchecked")
                Collection<String> players = (Collection<String>) invoke(teamPlayers, team);
                players.add(owner);
            }
            return teamModeConstructor != null
                    ? create(teamModeConstructor, team, add ? METHOD_ADD : METHOD_CHANGE)
                    : invoke(teamFactory, null, team, add);
        }

        Object removeTeam(String name) {
            Object team = create(teamConstructor, scoreboard, name);
            return teamModeConstructor != null
                    ? create(teamModeConstructor, team, METHOD_REMOVE)
                    : invoke(removeTeamFactory, null, team);
        }

        void send(Player player, Object packet) {
            try {
                Connection current = connection;
                if (current == null || current.playerType != player.getClass()) {
                    current = Connection.resolve(player.getClass(), packetType);
                    connection = current;
                }
                current.send(player, packet);
            } catch (InvocationTargetException failure) {
                throw new IllegalStateException("Cannot send sidebar packet to " + player.getName(),
                        failure.getCause());
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Cannot send sidebar packet to " + player.getName(),
                        failure);
            }
        }

        private Object component(String value) {
            Object[] parts = (Object[]) invoke(text, null, value);
            return parts.length > 0 ? parts[0] : ((Object[]) invoke(text, null, " "))[0];
        }

        private static Constructor<?> actionScoreConstructor(Class<?> packet) throws NoSuchMethodException {
            for (Constructor<?> constructor : packet.getConstructors()) {
                Class<?>[] types = constructor.getParameterTypes();
                if (types.length == 4 && types[0].isEnum() && types[1] == String.class
                        && types[2] == String.class && types[3] == int.class) {
                    return constructor;
                }
            }
            throw new NoSuchMethodException(packet.getName() + "(Action, String, String, int)");
        }

        private static Method playersMethod(Class<?> teamType) throws NoSuchMethodException {
            for (Method method : teamType.getMethods()) {
                if (!Modifier.isStatic(method.getModifiers()) && method.getParameterCount() == 0
                        && method.getReturnType() == Collection.class) {
                    return method;
                }
            }
            throw new NoSuchMethodException(teamType.getName() + " players");
        }

        /**
         * 1.12 至 1.16 的前缀 setter 带有可读名称；1.17 起显示名、前缀和后缀的 setter 签名相同，
         * 用格式化名称探测出真正的前缀 setter。
         */
        private Method prefixSetter(Class<?> teamType, Class<?> componentType)
                throws ReflectiveOperationException {
            Method named = optionalMethod(teamType, "setPrefix", componentType);
            if (named != null) {
                return named;
            }
            Method formatted = null;
            List<Method> setters = new ArrayList<Method>();
            for (Method method : teamType.getMethods()) {
                Class<?>[] types = method.getParameterTypes();
                if (Modifier.isStatic(method.getModifiers()) || types.length != 1 || types[0] != componentType) {
                    continue;
                }
                if (method.getReturnType() == void.class) {
                    setters.add(method);
                } else if (componentType.isAssignableFrom(method.getReturnType())) {
                    formatted = method;
                }
            }
            if (formatted == null) {
                throw new NoSuchMethodException(teamType.getName() + " formatted name");
            }
            for (Method setter : setters) {
                Object team = teamConstructor.newInstance(scoreboard, "klibprobe");
                setter.invoke(team, component("P"));
                Object name = formatted.invoke(team, component("N"));
                if ("PN".equals(name.getClass().getMethod("getString").invoke(name))) {
                    return setter;
                }
            }
            throw new NoSuchMethodException(teamType.getName() + " prefix setter among " + setters);
        }
    }

    private static final class Connection {
        private final Class<?> playerType;
        private final Method handle;
        private final Field field;
        private final Method send;

        private Connection(Class<?> playerType, Method handle, Field field, Method send) {
            this.playerType = playerType;
            this.handle = handle;
            this.field = field;
            this.send = send;
        }

        void send(Player player, Object packet) throws ReflectiveOperationException {
            send.invoke(field.get(handle.invoke(player)), packet);
        }

        static Connection resolve(Class<?> playerType, Class<?> packetType) throws NoSuchFieldException,
                NoSuchMethodException {
            Method handle = playerType.getMethod("getHandle");
            for (Class<?> type = handle.getReturnType(); type != null; type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (!Modifier.isStatic(field.getModifiers())
                            && "PlayerConnection".equals(field.getType().getSimpleName())) {
                        field.setAccessible(true);
                        return new Connection(playerType, handle, field, sendMethod(field.getType(), packetType));
                    }
                }
            }
            throw new NoSuchFieldException("player connection field on " + handle.getReturnType().getName());
        }

        private static Method sendMethod(Class<?> connectionType, Class<?> packetType)
                throws NoSuchMethodException {
            List<Method> candidates = new ArrayList<Method>();
            for (Method method : connectionType.getMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (!Modifier.isStatic(method.getModifiers()) && parameters.length == 1
                        && parameters[0] == packetType && method.getReturnType() == void.class) {
                    if ("sendPacket".equals(method.getName())) {
                        return method;
                    }
                    candidates.add(method);
                }
            }
            if (candidates.size() == 1) {
                return candidates.get(0);
            }
            throw new NoSuchMethodException("packet send method on " + connectionType.getName()
                    + ", candidates: " + candidates);
        }
    }

    private static Class<?> type(ClassLoader loader, String... names) throws ClassNotFoundException {
        for (String name : names) {
            try {
                return Class.forName(name, true, loader);
            } catch (ClassNotFoundException ignored) {
                // 尝试另一种包布局
            }
        }
        throw new ClassNotFoundException(String.join(" / ", names));
    }

    private static Constructor<?> constructor(Class<?> type, int parameterCount) throws NoSuchMethodException {
        Constructor<?> constructor = optionalConstructor(type, parameterCount);
        if (constructor == null) {
            throw new NoSuchMethodException(type.getName() + " constructor with " + parameterCount + " parameters");
        }
        return constructor;
    }

    private static Constructor<?> optionalConstructor(Class<?> type, int parameterCount) {
        for (Constructor<?> constructor : type.getConstructors()) {
            if (constructor.getParameterCount() == parameterCount) {
                return constructor;
            }
        }
        return null;
    }

    private static Constructor<?> optionalConstructor(Class<?> type, Class<?>... parameters) {
        try {
            return type.getConstructor(parameters);
        } catch (NoSuchMethodException absent) {
            return null;
        }
    }

    /** 1.13 起有按名称查找准则的静态方法；1.12 的准则接口只公开名称映射表。 */
    private static Object dummyCriteria(Class<?> criteriaType) throws ReflectiveOperationException {
        for (Method method : criteriaType.getMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (Modifier.isStatic(method.getModifiers()) && parameters.length == 1
                    && parameters[0] == String.class && method.getReturnType() == Optional.class) {
                Optional<?> dummy = (Optional<?>) method.invoke(null, "dummy");
                if (dummy.isPresent() && criteriaType.isInstance(dummy.get())) {
                    return dummy.get();
                }
            }
        }
        for (Field field : criteriaType.getFields()) {
            if (Modifier.isStatic(field.getModifiers()) && Map.class.isAssignableFrom(field.getType())) {
                Object dummy = ((Map<?, ?>) field.get(null)).get("dummy");
                if (criteriaType.isInstance(dummy)) {
                    return dummy;
                }
            }
        }
        throw new NoSuchFieldException(criteriaType.getName() + " dummy criteria");
    }

    private static Method optionalMethod(Class<?> type, String name, Class<?>... parameters) {
        try {
            return type.getMethod(name, parameters);
        } catch (NoSuchMethodException absent) {
            return null;
        }
    }

    private static Object invoke(Method method, Object target, Object... arguments) {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException failure) {
            throw new IllegalStateException("Cannot invoke " + method, failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot invoke " + method, failure);
        }
    }

    private static Object create(Constructor<?> constructor, Object... arguments) {
        try {
            return constructor.newInstance(arguments);
        } catch (InvocationTargetException failure) {
            throw new IllegalStateException("Cannot create " + constructor.getDeclaringClass().getName(),
                    failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot create " + constructor.getDeclaringClass().getName(),
                    failure);
        }
    }
}
