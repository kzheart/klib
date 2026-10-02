/*
 * 数据包侧边栏实现，改写自 TabooLib（https://github.com/TabooLib/taboolib）的
 * module/bukkit-nms/bukkit-nms-stable/src/main/kotlin/taboolib/module/nms/NMSScoreboardImpl.kt
 * 与 NMSScoreboardImpl26.kt：用 Java 反射代替 TabooLib 的 NMS 代理与重映射。
 *
 * Copyright (c) 2018 Bkm016
 * Licensed under the MIT License; the full license text is reproduced in THIRD_PARTY_NOTICES.md
 * and META-INF/LICENSE-TabooLib-Scoreboard.txt. Source revision: 0e3a911fc55624075b5c9abd4368cb5b063b022b.
 */
package me.kzheart.klib.compat.v26;

import me.kzheart.klib.compat.SidebarBridge;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Minecraft 26.2 及以上 26.x 版本的侧边栏数据包实现。
 *
 * <p>每行是一个带自定义显示文本的分数，计分项使用空白数字格式隐藏右侧分数，不需要队伍。
 * 优先使用 26.x 的非混淆 Mojang 类名，并保留 Spigot 类名候选，成员按签名查找。
 */
final class V26SidebarBridge implements SidebarBridge {
    private static final int MAX_LINES = 15;
    private static final int METHOD_ADD = 0;
    private static final int METHOD_REMOVE = 1;
    private static final int METHOD_CHANGE = 2;
    private static final String GAME_PACKETS = "net.minecraft.network.protocol.game.";

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
        updateLine(player, objective, index, text);
    }

    @Override
    public void updateLine(Player player, String objective, int index, String text) {
        Packets resolved = packets(player);
        resolved.send(player, resolved.score(owner(objective, index), objective, index, text));
    }

    @Override
    public void removeLine(Player player, String objective, int index) {
        Packets resolved = packets(player);
        resolved.send(player, resolved.resetScore(owner(objective, index), objective));
    }

    @Override
    public void remove(Player player, String objective, int lineCount) {
        Packets resolved = packets(player);
        resolved.send(player, resolved.setObjective(resolved.objective(objective, ""), METHOD_REMOVE));
    }

    /** 由不可见颜色代码组成的计分项持有者，避免与真实玩家名或其他插件冲突。 */
    static String owner(String objective, int index) {
        String key = objective + Character.forDigit(index >> 4 & 0xf, 16)
                + Character.forDigit(index & 0xf, 16);
        StringBuilder owner = new StringBuilder(key.length() * 2);
        for (int i = 0; i < key.length(); i++) {
            owner.append('§').append(key.charAt(i));
        }
        return owner.toString();
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
        private final Constructor<?> objective;
        private final Object scoreboard;
        private final Object criteria;
        private final Object renderType;
        private final Object blankFormat;
        private final Constructor<?> setObjective;
        private final Constructor<?> display;
        private final Object sidebarSlot;
        private final Constructor<?> score;
        private final Constructor<?> resetScore;
        private final Method text;
        private final boolean textArray;
        private final Class<?> packetType;
        private volatile Connection connection;

        private Packets(ClassLoader loader) throws ReflectiveOperationException {
            packetType = type(loader, "net.minecraft.network.protocol.Packet");
            Class<?> objectiveType = type(loader,
                    "net.minecraft.world.scores.Objective",
                    "net.minecraft.world.scores.ScoreboardObjective");
            objective = constructor(objectiveType, 7);
            Class<?>[] parameters = objective.getParameterTypes();
            scoreboard = parameters[0].getConstructor().newInstance();
            criteria = dummyCriteria(parameters[2]);
            renderType = parameters[4].getEnumConstants()[0];
            blankFormat = staticValue(type(loader, "net.minecraft.network.chat.numbers.BlankFormat"));
            setObjective = type(loader,
                    GAME_PACKETS + "ClientboundSetObjectivePacket",
                    GAME_PACKETS + "PacketPlayOutScoreboardObjective")
                    .getConstructor(objectiveType, int.class);
            display = displayConstructor(type(loader,
                    GAME_PACKETS + "ClientboundSetDisplayObjectivePacket",
                    GAME_PACKETS + "PacketPlayOutScoreboardDisplayObjective"), objectiveType);
            sidebarSlot = enumConstant(display.getParameterTypes()[0], "SIDEBAR", 1);
            score = type(loader,
                    GAME_PACKETS + "ClientboundSetScorePacket",
                    GAME_PACKETS + "PacketPlayOutScoreboardScore")
                    .getConstructor(String.class, String.class, int.class, Optional.class, Optional.class);
            resetScore = type(loader, GAME_PACKETS + "ClientboundResetScorePacket")
                    .getConstructor(String.class, String.class);
            Class<?> chat = Class.forName(
                    Bukkit.getServer().getClass().getPackage().getName() + ".util.CraftChatMessage",
                    true, loader);
            Method single = optionalMethod(chat, "fromStringOrEmpty", String.class);
            text = single != null ? single : chat.getMethod("fromString", String.class);
            textArray = single == null;
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
            return create(objective, scoreboard, name, criteria, component(title), renderType, false,
                    blankFormat);
        }

        Object setObjective(Object handle, int method) {
            return create(setObjective, handle, method);
        }

        Object display(Object handle) {
            return create(display, sidebarSlot, handle);
        }

        Object score(String owner, String objectiveName, int value, String line) {
            return create(score, owner, objectiveName, value, Optional.of(component(line)),
                    Optional.empty());
        }

        Object resetScore(String owner, String objectiveName) {
            return create(resetScore, owner, objectiveName);
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
            try {
                Object result = text.invoke(null, value);
                if (!textArray) {
                    return result;
                }
                Object[] parts = (Object[]) result;
                return parts.length > 0 ? parts[0] : ((Object[]) text.invoke(null, " "))[0];
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Cannot convert sidebar text", failure);
            }
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
                    String name = field.getType().getSimpleName();
                    if (!Modifier.isStatic(field.getModifiers())
                            && ("ServerGamePacketListenerImpl".equals(name) || "PlayerConnection".equals(name))) {
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
                    if ("send".equals(method.getName())) {
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
                // 尝试另一套映射名
            }
        }
        throw new ClassNotFoundException(String.join(" / ", names));
    }

    private static Constructor<?> constructor(Class<?> type, int parameterCount) throws NoSuchMethodException {
        for (Constructor<?> constructor : type.getConstructors()) {
            if (constructor.getParameterCount() == parameterCount) {
                return constructor;
            }
        }
        throw new NoSuchMethodException(type.getName() + " constructor with " + parameterCount + " parameters");
    }

    private static Constructor<?> displayConstructor(Class<?> packet, Class<?> objectiveType)
            throws NoSuchMethodException {
        for (Constructor<?> constructor : packet.getConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length == 2 && parameters[0].isEnum() && parameters[1] == objectiveType) {
                return constructor;
            }
        }
        throw new NoSuchMethodException(packet.getName() + "(DisplaySlot, Objective)");
    }

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
        return criteriaType.getField("DUMMY").get(null);
    }

    private static Object staticValue(Class<?> type) throws ReflectiveOperationException {
        for (Field field : type.getFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == type) {
                return field.get(null);
            }
        }
        throw new NoSuchFieldException(type.getName() + " singleton");
    }

    private static Object enumConstant(Class<?> type, String name, int fallbackOrdinal) {
        Object[] constants = type.getEnumConstants();
        for (Object constant : constants) {
            if (name.equals(((Enum<?>) constant).name())) {
                return constant;
            }
        }
        return constants[fallbackOrdinal];
    }

    private static Method optionalMethod(Class<?> type, String name, Class<?>... parameters) {
        try {
            return type.getMethod(name, parameters);
        } catch (NoSuchMethodException absent) {
            return null;
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
