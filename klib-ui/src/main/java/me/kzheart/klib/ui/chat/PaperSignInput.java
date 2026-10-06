package me.kzheart.klib.ui.chat;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import me.kzheart.klib.KLogger;
import me.kzheart.klib.lang.BukkitAdventure;
import me.kzheart.klib.scope.Disposable;
import me.kzheart.klib.scope.Scope;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

/** Paper 虚拟告示牌：向玩家发送假告示牌并通过 UncheckedSignChangeEvent 取回文字，不改动世界方块。 */
final class PaperSignInput implements ChatPanelSignInput, Listener, Disposable {
    private final Scope owner;
    private final KLogger logger;
    private final Method block;
    private final Object front;
    private final Method openVirtualSign;
    private final Method editedPosition;
    private final Method blockX;
    private final Method blockY;
    private final Method blockZ;
    private final Method lines;
    private final Method sendBlockChange;
    private final Method getBlockData;
    private final Object signData;
    private final Map<UUID, Pending> pending = new HashMap<UUID, Pending>();

    private PaperSignInput(Scope owner, KLogger logger, Class<?> event) throws ReflectiveOperationException {
        this.owner = owner;
        this.logger = logger;
        ClassLoader loader = Bukkit.class.getClassLoader();
        Class<?> position = loader.loadClass("io.papermc.paper.math.Position");
        Class<?> side = loader.loadClass("org.bukkit.block.sign.Side");
        Class<?> blockData = loader.loadClass("org.bukkit.block.data.BlockData");
        block = position.getMethod("block", int.class, int.class, int.class);
        front = side.getField("FRONT").get(null);
        openVirtualSign = Player.class.getMethod("openVirtualSign", position, side);
        editedPosition = event.getMethod("getEditedBlockPosition");
        blockX = position.getMethod("blockX");
        blockY = position.getMethod("blockY");
        blockZ = position.getMethod("blockZ");
        lines = event.getMethod("lines");
        sendBlockChange = Player.class.getMethod("sendBlockChange", Location.class, blockData);
        getBlockData = Block.class.getMethod("getBlockData");
        signData = Server.class.getMethod("createBlockData", String.class).invoke(Bukkit.getServer(), "minecraft:oak_sign");
    }

    /** 服务端不提供虚拟告示牌 API 时返回 null。 */
    @SuppressWarnings("unchecked")
    static PaperSignInput detect(Scope owner, Plugin plugin, KLogger logger) {
        Class<?> event;
        PaperSignInput input;
        try {
            event = Bukkit.class.getClassLoader().loadClass("io.papermc.paper.event.packet.UncheckedSignChangeEvent");
            input = new PaperSignInput(owner, logger, event);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException unsupported) {
            return null;
        }
        owner.install(input);
        plugin.getServer().getPluginManager().registerEvents(input, plugin);
        plugin.getServer().getPluginManager().registerEvent((Class<? extends Event>) event, input, EventPriority.NORMAL,
                (listener, fired) -> { if (event.isInstance(fired)) input.submitted(fired); }, plugin, false);
        return input;
    }

    @Override public boolean open(Player player, String[] text, Consumer<String[]> submitted) {
        cancel(player);
        Location location = player.getLocation().getBlock().getLocation();
        try {
            sendBlockChange.invoke(player, location, signData);
            player.sendSignChange(location, text);
            openVirtualSign.invoke(player, block.invoke(null, location.getBlockX(), location.getBlockY(), location.getBlockZ()), front);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            restore(player, location);
            logger.error("无法打开告示牌输入", failure instanceof InvocationTargetException ? failure.getCause() : failure);
            return false;
        }
        pending.put(player.getUniqueId(), new Pending(location, submitted));
        return true;
    }

    @Override public void cancel(Player player) {
        Pending previous = pending.remove(player.getUniqueId());
        if (previous != null) restore(player, previous.location);
    }

    private void submitted(Event event) {
        if (!Bukkit.isPrimaryThread()) {
            owner.syncExecutor().execute(() -> submitted(event));
            return;
        }
        Player player = ((PlayerEvent) event).getPlayer();
        Pending current = pending.get(player.getUniqueId());
        if (current == null) return;
        String[] values;
        try {
            Object position = editedPosition.invoke(event);
            if ((Integer) blockX.invoke(position) != current.location.getBlockX()
                    || (Integer) blockY.invoke(position) != current.location.getBlockY()
                    || (Integer) blockZ.invoke(position) != current.location.getBlockZ()) return;
            List<?> components = (List<?>) lines.invoke(event);
            List<String> text = new ArrayList<String>();
            for (Object component : components) text.add(BukkitAdventure.plain(component));
            values = text.toArray(new String[0]);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            logger.error("无法读取告示牌输入", failure);
            values = new String[0];
        }
        pending.remove(player.getUniqueId());
        restore(player, current.location);
        current.submitted.accept(values);
    }

    private void restore(Player player, Location location) {
        if (!player.isOnline()) return;
        try { sendBlockChange.invoke(player, location, getBlockData.invoke(location.getBlock())); }
        catch (ReflectiveOperationException | RuntimeException failure) { logger.error("无法还原告示牌输入位置", failure); }
    }

    @EventHandler public void quit(PlayerQuitEvent event) { pending.remove(event.getPlayer().getUniqueId()); }

    @Override public void dispose() {
        for (Map.Entry<UUID, Pending> entry : new ArrayList<Map.Entry<UUID, Pending>>(pending.entrySet())) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null) restore(player, entry.getValue().location);
        }
        pending.clear();
        HandlerList.unregisterAll(this);
    }

    private static final class Pending {
        final Location location;
        final Consumer<String[]> submitted;
        Pending(Location location, Consumer<String[]> submitted) { this.location = location; this.submitted = submitted; }
    }
}
