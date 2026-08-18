package me.kzheart.klib.command;

import me.kzheart.klib.lang.RichText;
import me.kzheart.klib.lang.RichTextSegment;
import me.kzheart.klib.lang.TextAction;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class SpigotRichTextSink implements RichTextSink {
    public static final SpigotRichTextSink INSTANCE = new SpigotRichTextSink(
            new ReflectionInteractiveDelivery());

    private final InteractiveDelivery interactive;

    SpigotRichTextSink(InteractiveDelivery interactive) {
        if (interactive == null) {
            throw new NullPointerException("interactive");
        }
        this.interactive = interactive;
    }

    @Override
    public void send(CommandSender sender, RichText text) {
        if (sender instanceof Player) {
            if (!interactive.trySend((Player) sender, text)) {
                sender.sendMessage(text.legacyText());
            }
            return;
        }
        // 控制台保留颜色，现代终端可正常渲染 legacy 颜色码。
        sender.sendMessage(text.legacyText());
    }

    interface InteractiveDelivery {
        boolean trySend(Player player, RichText text);
    }

    static final class RuntimeMethodCache {
        private final String methodName;
        private final Class<?>[] parameterTypes;
        private final ConcurrentMap<Class<?>, Optional<Method>> methods =
                new ConcurrentHashMap<Class<?>, Optional<Method>>();
        private final AtomicInteger resolutions = new AtomicInteger();

        RuntimeMethodCache(String methodName, Class<?>... parameterTypes) {
            this.methodName = methodName;
            this.parameterTypes = Arrays.copyOf(parameterTypes, parameterTypes.length);
        }

        Method find(Class<?> runtimeType) {
            return methods.computeIfAbsent(runtimeType, this::resolve).orElse(null);
        }

        int resolutionCount() {
            return resolutions.get();
        }

        private Optional<Method> resolve(Class<?> runtimeType) {
            resolutions.incrementAndGet();
            try {
                return Optional.of(runtimeType.getMethod(methodName, parameterTypes));
            } catch (NoSuchMethodException ignored) {
                return Optional.empty();
            } catch (SecurityException ignored) {
                return Optional.empty();
            } catch (LinkageError ignored) {
                return Optional.empty();
            }
        }
    }

    private static final class ReflectionInteractiveDelivery implements InteractiveDelivery {
        /** Bungee Chat API 反射句柄静态缓存，避免每条消息重复 Class.forName/getMethod。 */
        private static final BungeeApi API = BungeeApi.resolve();
        private final RuntimeMethodCache spigotMethods = new RuntimeMethodCache("spigot");
        private final RuntimeMethodCache sendMessageMethods = API == null
                ? null
                : new RuntimeMethodCache(
                        "sendMessage",
                        Array.newInstance(API.baseComponent, 0).getClass());

        @Override
        public boolean trySend(Player player, RichText text) {
            if (API == null) {
                return false;
            }
            try {
                Object components = Array.newInstance(API.baseComponent, text.segments().size());
                int index = 0;
                for (RichTextSegment segment : text.segments()) {
                    Object component = API.textConstructor.newInstance(segment.text());
                    if (segment.color() != null) {
                        API.setColor.invoke(component, API.resolveColor(segment));
                    }
                    if (segment.bold()) {
                        API.setBold.invoke(component, Boolean.TRUE);
                    }
                    if (segment.italic()) {
                        API.setItalic.invoke(component, Boolean.TRUE);
                    }
                    if (segment.underlined()) {
                        API.setUnderlined.invoke(component, Boolean.TRUE);
                    }
                    if (segment.strikethrough()) {
                        API.setStrikethrough.invoke(component, Boolean.TRUE);
                    }
                    if (segment.obfuscated()) {
                        API.setObfuscated.invoke(component, Boolean.TRUE);
                    }
                    if (segment.hover() != null) {
                        Object hoverText = API.textConstructor.newInstance(
                                segment.hover().value());
                        Object hoverContents = Array.newInstance(API.baseComponent, 1);
                        Array.set(hoverContents, 0, hoverText);
                        Object action = enumValue(API.hoverAction, "SHOW_TEXT");
                        Object event = API.hoverConstructor.newInstance(action, hoverContents);
                        API.setHoverEvent.invoke(component, event);
                    }
                    if (segment.click() != null) {
                        Object action = enumValue(
                                API.clickAction,
                                clickActionName(segment.click()));
                        Object event = API.clickConstructor.newInstance(
                                action,
                                segment.click().value());
                        API.setClickEvent.invoke(component, event);
                    }
                    Array.set(components, index++, component);
                }

                Method spigotMethod = spigotMethods.find(player.getClass());
                if (spigotMethod == null) {
                    return false;
                }
                Object spigot = spigotMethod.invoke(player);
                if (spigot == null) {
                    return false;
                }
                Method sendMessage = sendMessageMethods.find(spigot.getClass());
                if (sendMessage == null) {
                    return false;
                }
                sendMessage.invoke(spigot, components);
                return true;
            } catch (ReflectiveOperationException failure) {
                return false;
            } catch (LinkageError failure) {
                return false;
            } catch (RuntimeException failure) {
                return false;
            }
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

        private static final class BungeeApi {
            private final Class<?> baseComponent;
            private final Class<?> chatColor;
            private final Class<?> hoverAction;
            private final Class<?> clickAction;
            private final Constructor<?> textConstructor;
            private final Constructor<?> hoverConstructor;
            private final Constructor<?> clickConstructor;
            private final Method setColor;
            private final Method setBold;
            private final Method setItalic;
            private final Method setUnderlined;
            private final Method setStrikethrough;
            private final Method setObfuscated;
            private final Method setHoverEvent;
            private final Method setClickEvent;
            private final Method chatColorOf;

            private BungeeApi(
                    Class<?> baseComponent,
                    Class<?> chatColor,
                    Class<?> hoverAction,
                    Class<?> clickAction,
                    Constructor<?> textConstructor,
                    Constructor<?> hoverConstructor,
                    Constructor<?> clickConstructor,
                    Method setColor,
                    Method setBold,
                    Method setItalic,
                    Method setUnderlined,
                    Method setStrikethrough,
                    Method setObfuscated,
                    Method setHoverEvent,
                    Method setClickEvent,
                    Method chatColorOf
            ) {
                this.baseComponent = baseComponent;
                this.chatColor = chatColor;
                this.hoverAction = hoverAction;
                this.clickAction = clickAction;
                this.textConstructor = textConstructor;
                this.hoverConstructor = hoverConstructor;
                this.clickConstructor = clickConstructor;
                this.setColor = setColor;
                this.setBold = setBold;
                this.setItalic = setItalic;
                this.setUnderlined = setUnderlined;
                this.setStrikethrough = setStrikethrough;
                this.setObfuscated = setObfuscated;
                this.setHoverEvent = setHoverEvent;
                this.setClickEvent = setClickEvent;
                this.chatColorOf = chatColorOf;
            }

            Object resolveColor(RichTextSegment segment) throws ReflectiveOperationException {
                if (!segment.color().isLegacy() && chatColorOf != null) {
                    return chatColorOf.invoke(null, segment.color().toString());
                }
                return enumValue(
                        chatColor,
                        segment.color()
                                .nearestLegacy()
                                .toString()
                                .toUpperCase(Locale.ROOT));
            }

            static BungeeApi resolve() {
                try {
                    Class<?> baseComponent =
                            Class.forName("net.md_5.bungee.api.chat.BaseComponent");
                    Class<?> textComponent =
                            Class.forName("net.md_5.bungee.api.chat.TextComponent");
                    Class<?> chatColor = Class.forName("net.md_5.bungee.api.ChatColor");
                    Class<?> hoverEvent = Class.forName("net.md_5.bungee.api.chat.HoverEvent");
                    Class<?> hoverAction =
                            Class.forName("net.md_5.bungee.api.chat.HoverEvent$Action");
                    Class<?> clickEvent = Class.forName("net.md_5.bungee.api.chat.ClickEvent");
                    Class<?> clickAction =
                            Class.forName("net.md_5.bungee.api.chat.ClickEvent$Action");
                    Class<?> componentArray = Array.newInstance(baseComponent, 0).getClass();
                    Method chatColorOf;
                    try {
                        chatColorOf = chatColor.getMethod("of", String.class);
                    } catch (NoSuchMethodException ignored) {
                        // 旧版 Bungee API 无 RGB 支持，降级到最接近的具名颜色。
                        chatColorOf = null;
                    }
                    return new BungeeApi(
                            baseComponent,
                            chatColor,
                            hoverAction,
                            clickAction,
                            textComponent.getConstructor(String.class),
                            hoverEvent.getConstructor(hoverAction, componentArray),
                            clickEvent.getConstructor(clickAction, String.class),
                            baseComponent.getMethod("setColor", chatColor),
                            baseComponent.getMethod("setBold", Boolean.class),
                            baseComponent.getMethod("setItalic", Boolean.class),
                            baseComponent.getMethod("setUnderlined", Boolean.class),
                            baseComponent.getMethod("setStrikethrough", Boolean.class),
                            baseComponent.getMethod("setObfuscated", Boolean.class),
                            baseComponent.getMethod("setHoverEvent", hoverEvent),
                            baseComponent.getMethod("setClickEvent", clickEvent),
                            chatColorOf);
                } catch (ReflectiveOperationException ignored) {
                    return null;
                } catch (LinkageError ignored) {
                    return null;
                } catch (RuntimeException ignored) {
                    return null;
                }
            }
        }
    }
}
