package me.kzheart.klib.lang;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;

/** 兼容 CommandSender 且不在编译期依赖 Bukkit 的接收者。 */
public final class MessageRecipient {
    private final Object handle;
    private final boolean console;

    private MessageRecipient(Object handle, boolean console) {
        this.handle = Objects.requireNonNull(handle, "handle");
        this.console = console;
    }

    public static MessageRecipient commandSender(Object sender) {
        Objects.requireNonNull(sender, "sender");
        return new MessageRecipient(sender, isConsoleType(sender.getClass()));
    }

    public static MessageRecipient of(Object handle, boolean console) {
        return new MessageRecipient(handle, console);
    }

    public Object handle() {
        return handle;
    }

    public boolean isConsole() {
        return console;
    }

    public void sendLegacy(String text) {
        try {
            Method method = handle.getClass().getMethod("sendMessage", String.class);
            method.invoke(handle, text);
        } catch (NoSuchMethodException exception) {
            throw new IllegalArgumentException("Recipient has no sendMessage(String): " + handle.getClass().getName(), exception);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Cannot access sendMessage(String)", exception);
        } catch (InvocationTargetException exception) {
            throw new IllegalStateException("sendMessage(String) failed", exception.getCause());
        }
    }

    private static boolean isConsoleType(Class<?> type) {
        if ("ConsoleCommandSender".equals(type.getSimpleName())) {
            return true;
        }
        for (Class<?> contract : type.getInterfaces()) {
            if (isConsoleType(contract)) {
                return true;
            }
        }
        Class<?> parent = type.getSuperclass();
        return parent != null && isConsoleType(parent);
    }
}
