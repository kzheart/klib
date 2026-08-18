package me.kzheart.klib.lang;

import org.bukkit.entity.Player;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Locale;

final class ReflectionBukkitComponentSender implements BukkitComponentSender {
    static final ReflectionBukkitComponentSender INSTANCE = new ReflectionBukkitComponentSender();

    private ReflectionBukkitComponentSender() {
    }

    @Override
    public boolean send(Player player, RichText message, boolean actionBar) {
        Bungee bungee = Bungee.INSTANCE;
        if (bungee == null) {
            return false;
        }
        try {
            Object components = Array.newInstance(bungee.baseComponent, message.segments().size());
            int index = 0;
            for (RichTextSegment segment : message.segments()) {
                Object component = bungee.textConstructor.newInstance(segment.text());
                applyStyle(bungee, component, segment);
                applyActions(bungee, component, segment);
                Array.set(components, index++, component);
            }
            Object spigot = bungee.spigotMethod.invoke(player);
            if (spigot == null) {
                return false;
            }
            if (actionBar) {
                bungee.sendActionBarMethod.invoke(spigot, bungee.actionBarType, components);
            } else {
                bungee.sendChatMethod.invoke(spigot, components);
            }
            return true;
        } catch (ReflectiveOperationException failure) {
            return false;
        } catch (LinkageError failure) {
            return false;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private static void applyStyle(Bungee bungee, Object component, RichTextSegment segment)
            throws ReflectiveOperationException {
        if (segment.color() != null) {
            bungee.setColorMethod.invoke(component, resolveColor(bungee, segment.color()));
        }
        setBoolean(bungee.setBoldMethod, component, segment.bold());
        setBoolean(bungee.setItalicMethod, component, segment.italic());
        setBoolean(bungee.setUnderlinedMethod, component, segment.underlined());
        setBoolean(bungee.setStrikethroughMethod, component, segment.strikethrough());
        setBoolean(bungee.setObfuscatedMethod, component, segment.obfuscated());
    }

    private static void applyActions(Bungee bungee, Object component, RichTextSegment segment)
            throws ReflectiveOperationException {
        if (segment.hover() != null) {
            Object hoverText = bungee.textConstructor.newInstance(segment.hover().value());
            Object hoverContents = Array.newInstance(bungee.baseComponent, 1);
            Array.set(hoverContents, 0, hoverText);
            Object action = enumValue(bungee.hoverAction, "SHOW_TEXT");
            Object event = bungee.hoverEventConstructor.newInstance(action, hoverContents);
            bungee.setHoverEventMethod.invoke(component, event);
        }
        if (segment.click() != null) {
            Object action = enumValue(bungee.clickAction, clickActionName(segment.click()));
            Object event = bungee.clickEventConstructor.newInstance(action, segment.click().value());
            bungee.setClickEventMethod.invoke(component, event);
        }
    }

    private static void setBoolean(Method method, Object component, boolean value)
            throws ReflectiveOperationException {
        if (value) {
            method.invoke(component, Boolean.TRUE);
        }
    }

    private static Object resolveColor(Bungee bungee, MessageColor color)
            throws ReflectiveOperationException {
        if (!color.isLegacy() && bungee.chatColorOfMethod != null) {
            return bungee.chatColorOfMethod.invoke(null, color.toString());
        }
        return enumValue(
                bungee.chatColor,
                color.nearestLegacy().toString().toUpperCase(Locale.ROOT));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object enumValue(Class<?> type, String value) {
        return Enum.valueOf((Class<? extends Enum>) type.asSubclass(Enum.class), value);
    }

    private static String clickActionName(TextAction action) {
        if (action.type() == TextAction.Type.RUN_COMMAND) {
            return "RUN_COMMAND";
        }
        if (action.type() == TextAction.Type.SUGGEST_COMMAND) {
            return "SUGGEST_COMMAND";
        }
        if (action.type() == TextAction.Type.OPEN_URL) {
            return "OPEN_URL";
        }
        throw new IllegalArgumentException("Unsupported click action: " + action.type());
    }

    /** 仅解析一次的反射句柄；Bungee 聊天不可用时 INSTANCE 为 null。 */
    private static final class Bungee {
        static final Bungee INSTANCE = resolve();

        final Class<?> baseComponent;
        final Class<?> chatColor;
        final Class<?> hoverAction;
        final Class<?> clickAction;
        final Constructor<?> textConstructor;
        final Constructor<?> hoverEventConstructor;
        final Constructor<?> clickEventConstructor;
        final Method setColorMethod;
        final Method setBoldMethod;
        final Method setItalicMethod;
        final Method setUnderlinedMethod;
        final Method setStrikethroughMethod;
        final Method setObfuscatedMethod;
        final Method setHoverEventMethod;
        final Method setClickEventMethod;
        final Method chatColorOfMethod;
        final Method spigotMethod;
        final Method sendChatMethod;
        final Method sendActionBarMethod;
        final Object actionBarType;

        private Bungee() throws ReflectiveOperationException {
            baseComponent = Class.forName("net.md_5.bungee.api.chat.BaseComponent");
            Class<?> textComponent = Class.forName("net.md_5.bungee.api.chat.TextComponent");
            chatColor = Class.forName("net.md_5.bungee.api.ChatColor");
            Class<?> hoverEvent = Class.forName("net.md_5.bungee.api.chat.HoverEvent");
            hoverAction = Class.forName("net.md_5.bungee.api.chat.HoverEvent$Action");
            Class<?> clickEvent = Class.forName("net.md_5.bungee.api.chat.ClickEvent");
            clickAction = Class.forName("net.md_5.bungee.api.chat.ClickEvent$Action");
            Class<?> messageType = Class.forName("net.md_5.bungee.api.ChatMessageType");
            Class<?> componentArray = Array.newInstance(baseComponent, 0).getClass();
            textConstructor = textComponent.getConstructor(String.class);
            hoverEventConstructor = hoverEvent.getConstructor(hoverAction, componentArray);
            clickEventConstructor = clickEvent.getConstructor(clickAction, String.class);
            setColorMethod = baseComponent.getMethod("setColor", chatColor);
            setBoldMethod = baseComponent.getMethod("setBold", Boolean.class);
            setItalicMethod = baseComponent.getMethod("setItalic", Boolean.class);
            setUnderlinedMethod = baseComponent.getMethod("setUnderlined", Boolean.class);
            setStrikethroughMethod = baseComponent.getMethod("setStrikethrough", Boolean.class);
            setObfuscatedMethod = baseComponent.getMethod("setObfuscated", Boolean.class);
            setHoverEventMethod = baseComponent.getMethod("setHoverEvent", hoverEvent);
            setClickEventMethod = baseComponent.getMethod("setClickEvent", clickEvent);
            chatColorOfMethod = optionalMethod(chatColor, "of", String.class);
            spigotMethod = Player.class.getMethod("spigot");
            Class<?> spigotType = spigotMethod.getReturnType();
            sendChatMethod = spigotType.getMethod("sendMessage", componentArray);
            sendActionBarMethod = spigotType.getMethod("sendMessage", messageType, componentArray);
            actionBarType = enumValue(messageType, "ACTION_BAR");
        }

        private static Bungee resolve() {
            try {
                return new Bungee();
            } catch (ReflectiveOperationException unavailable) {
                return null;
            } catch (LinkageError unavailable) {
                return null;
            } catch (RuntimeException unavailable) {
                return null;
            }
        }

        private static Method optionalMethod(Class<?> type, String name, Class<?>... parameters) {
            try {
                return type.getMethod(name, parameters);
            } catch (NoSuchMethodException missing) {
                // Spigot 1.12 没有 ChatColor.of，因此改用最接近的旧版颜色。
                return null;
            }
        }
    }
}
