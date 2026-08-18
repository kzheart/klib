package me.kzheart.klib.command;

import org.bukkit.Bukkit;
import org.bukkit.Server;

import java.lang.reflect.Method;
import java.util.logging.Level;
import java.util.logging.Logger;

/** 探测并调用 Paper 的 {@code syncCommands}，向客户端刷新命令树。 */
final class ServerCommandSync {
    private static final Logger LOGGER = Logger.getLogger(ServerCommandSync.class.getName());

    private ServerCommandSync() {
    }

    /** syncCommands 不可用（Spigot / 无服务端环境）时返回 false，不抛异常。 */
    static boolean trySyncCommands() {
        Server server = Bukkit.getServer();
        if (server == null) {
            return false;
        }
        try {
            Method method = server.getClass().getMethod("syncCommands");
            method.invoke(server);
            return true;
        } catch (NoSuchMethodException ignored) {
            // Spigot 没有 syncCommands，命令树在下一次登录时自然生效。
            return false;
        } catch (ReflectiveOperationException failure) {
            LOGGER.log(Level.WARNING, "无法同步客户端命令树", failure);
            return false;
        } catch (RuntimeException failure) {
            LOGGER.log(Level.WARNING, "无法同步客户端命令树", failure);
            return false;
        }
    }
}
