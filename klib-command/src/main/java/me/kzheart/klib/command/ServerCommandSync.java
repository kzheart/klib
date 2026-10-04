package me.kzheart.klib.command;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.function.BooleanSupplier;
import java.util.Queue;
import java.util.WeakHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Single entry for command mutation exclusion and coalesced client refresh. */
final class ServerCommandSync {
    private static final Logger LOGGER = Logger.getLogger(ServerCommandSync.class.getName());
    private static final Map<Server, ServerCommandSync> SERVERS = new WeakHashMap<Server, ServerCommandSync>();
    private final Runnable sync;
    private final Queue<Runnable> mainQueue;
    private final ThreadPoolExecutor builders;
    private final BooleanSupplier stopping;
    private boolean pending;
    private boolean mutating;
    private final Queue<Runnable> mutations = new ArrayDeque<Runnable>();

    ServerCommandSync(Runnable sync, Queue<Runnable> mainQueue, ThreadPoolExecutor builders,
                      BooleanSupplier stopping) {
        this.sync = sync;
        this.mainQueue = mainQueue;
        this.builders = builders;
        this.stopping = stopping;
    }

    static boolean requestSyncCommands() {
        Server server = Bukkit.getServer();
        ServerCommandSync coordinator = discover(server);
        if (coordinator == null) return false;
        coordinator.request();
        return true;
    }

    static void mutate(Runnable mutation) {
        ServerCommandSync coordinator = discover(Bukkit.getServer());
        if (coordinator == null || coordinator.builders == null) mutation.run();
        else coordinator.enqueueMutation(mutation);
    }

    void enqueueMutation(Runnable mutation) {
        if (stopping.getAsBoolean()) return;
        mutations.add(mutation);
        if (mutating) return;
        mutating = true;
        try {
            CommandMutationGate.submit(builders, mainQueue, new Runnable() {
                @Override public void run() {
                    try {
                        while (!mutations.isEmpty()) {
                            Runnable next = mutations.remove();
                            if (stopping.getAsBoolean()) continue;
                            try { next.run(); }
                            catch (RuntimeException failure) { LOGGER.log(Level.SEVERE, "命令变更失败: " + next, failure); }
                        }
                    } finally {
                        mutating = false;
                    }
                }
            }, failure -> {
                LOGGER.log(Level.SEVERE, "无法安全修改客户端命令树", failure);
                mainQueue.add(new Runnable() {
                    @Override public void run() {
                        mutations.clear();
                        mutating = false;
                    }
                });
            });
        } catch (RuntimeException failure) {
            mutations.clear();
            mutating = false;
            throw failure;
        }
    }

    void request() {
        if (stopping.getAsBoolean() || pending) return;
        if (mainQueue == null) {
            sync.run();
            return;
        }
        pending = true;
        // Server-owned queue remains available when the calling plugin has already been disabled.
        mainQueue.add(new Runnable() {
            @Override public void run() {
                pending = false;
                if (!stopping.getAsBoolean()) sync.run();
            }
        });
    }

    private static synchronized ServerCommandSync discover(final Server server) {
        if (server == null) return null;
        if (SERVERS.containsKey(server)) return SERVERS.get(server);
        try {
            final Method sync = server.getClass().getMethod("syncCommands");
            Queue<Runnable> mainQueue = null;
            ThreadPoolExecutor builders = null;
            try {
                Object minecraft = server.getClass().getMethod("getServer").invoke(server);
                @SuppressWarnings("unchecked") Queue<Runnable> queue = (Queue<Runnable>) minecraft.getClass().getField("processQueue").get(minecraft);
                mainQueue = queue;
                Object commands = minecraft.getClass().getMethod("getCommands").invoke(minecraft);
                Field pool = commands.getClass().getField("COMMAND_SENDING_POOL");
                Object executor = pool.get(null);
                if (!(executor instanceof ThreadPoolExecutor)) throw new IllegalStateException("Unsupported Paper command-building executor");
                builders = (ThreadPoolExecutor) executor;
            } catch (NoSuchMethodException ignored) {
                // Platforms without the Paper async builder keep their existing synchronous registry.
            } catch (NoSuchFieldException ignored) {
                // No Paper async builder is exposed on this platform.
            }
            Method stop = null;
            try { stop = server.getClass().getMethod("isStopping"); } catch (NoSuchMethodException ignored) { }
            final Method stopping = stop;
            ServerCommandSync coordinator = new ServerCommandSync(new Runnable() {
                @Override public void run() {
                    try { sync.invoke(server); }
                    catch (ReflectiveOperationException failure) { LOGGER.log(Level.WARNING, "无法同步客户端命令树", failure); }
                }
            }, mainQueue, builders, () -> {
                if (stopping == null) return false;
                try { return Boolean.TRUE.equals(stopping.invoke(server)); }
                catch (ReflectiveOperationException failure) { throw new IllegalStateException("Cannot query server shutdown state", failure); }
            });
            SERVERS.put(server, coordinator);
            return coordinator;
        } catch (NoSuchMethodException ignored) {
            SERVERS.put(server, null);
            return null;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot coordinate the server command registry", failure);
        }
    }
}
