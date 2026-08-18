package me.kzheart.klib.command;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class TestSenders {
    private TestSenders() {
    }

    static SenderFixture console(String... permissions) {
        return create(false, "console", permissions);
    }

    static SenderFixture player(String name, String... permissions) {
        return create(true, name, permissions);
    }

    private static SenderFixture create(boolean player, String name, String[] permissions) {
        Set<String> granted = new HashSet<String>();
        Collections.addAll(granted, permissions);
        List<String> messages = new ArrayList<String>();
        InvocationHandler handler = new SenderHandler(name, granted, messages);
        Class<?> type = player ? Player.class : CommandSender.class;
        CommandSender sender = (CommandSender) Proxy.newProxyInstance(
                TestSenders.class.getClassLoader(),
                new Class<?>[]{type},
                handler);
        return new SenderFixture(sender, messages);
    }

    static final class SenderFixture {
        private final CommandSender sender;
        private final List<String> messages;

        private SenderFixture(CommandSender sender, List<String> messages) {
            this.sender = sender;
            this.messages = messages;
        }

        CommandSender sender() {
            return sender;
        }

        List<String> messages() {
            return messages;
        }
    }

    private static final class SenderHandler implements InvocationHandler {
        private final String name;
        private final Set<String> permissions;
        private final List<String> messages;

        private SenderHandler(String name, Set<String> permissions, List<String> messages) {
            this.name = name;
            this.permissions = permissions;
            this.messages = messages;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String methodName = method.getName();
            if ("getName".equals(methodName)) {
                return name;
            }
            if ("hasPermission".equals(methodName)) {
                return permissions.contains(args[0]);
            }
            if ("isPermissionSet".equals(methodName)) {
                return permissions.contains(args[0]);
            }
            if ("sendMessage".equals(methodName)) {
                if (args[0] instanceof String[]) {
                    Collections.addAll(messages, (String[]) args[0]);
                } else {
                    messages.add(String.valueOf(args[0]));
                }
                return null;
            }
            if ("toString".equals(methodName)) {
                return name;
            }
            if ("hashCode".equals(methodName)) {
                return System.identityHashCode(proxy);
            }
            if ("equals".equals(methodName)) {
                return proxy == args[0];
            }
            Class<?> returnType = method.getReturnType();
            if (returnType == Boolean.TYPE) {
                return Boolean.FALSE;
            }
            if (returnType == Integer.TYPE) {
                return Integer.valueOf(0);
            }
            if (returnType == Long.TYPE) {
                return Long.valueOf(0L);
            }
            if (returnType == Float.TYPE) {
                return Float.valueOf(0.0F);
            }
            if (returnType == Double.TYPE) {
                return Double.valueOf(0.0D);
            }
            return null;
        }
    }
}
