/* Player operator semantics: Copyright (c) 2018 Bkm016, MIT. See THIRD_PARTY_NOTICES.md. */
package me.kzheart.klib.script;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import javax.script.Bindings;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import javax.script.SimpleBindings;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

/**
 * Bukkit 下的默认宿主服务：消息、命令（含 as op）、发送者、玩家属性读写、延迟、日志、
 * 游戏语句平台、ItemStack/ItemMeta 属性，以及服务端存在 JavaScript 引擎时的 {@code js} 语句。
 * 所有服务都要求在主线程调用；引擎的续接执行器应为主线程调度器。
 */
public final class BukkitScriptServices {
    private BukkitScriptServices() { }

    public static ScriptContext.Builder apply(ScriptContext.Builder builder, Plugin plugin) {
        return apply(builder, plugin, null);
    }

    /** @param scoreboard {@code scoreboard} 语句的侧边栏实现，lines 为 null 表示移除；为 null 时该语句不可用 */
    public static ScriptContext.Builder apply(ScriptContext.Builder builder, Plugin plugin,
                                              BiConsumer<Player, List<String>> scoreboard) {
        builder.service(MessageSink.class, (sender, message) -> commandSender(sender).sendMessage(message));
        builder.service(CommandSink.class, new CommandSink() {
            @Override public Object dispatch(Object sender, String command) {
                return Boolean.valueOf(Bukkit.dispatchCommand(commandSender(sender), command));
            }
            @Override public Object dispatchAsOperator(Object sender, String command) {
                Player player = player(sender);
                boolean op = player.isOp();
                player.setOp(true);
                try { return Boolean.valueOf(Bukkit.dispatchCommand(player, command)); }
                finally { player.setOp(op); }
            }
        });
        builder.service(ScriptSenderQuery.class, new ScriptSenderQuery() {
            @Override public boolean isPlayer(Object sender) { return sender instanceof Player; }
            @Override public String name(Object sender) { return commandSender(sender).getName(); }
            @Override public boolean isOnline(Object sender) { return !(sender instanceof Player) || ((Player) sender).isOnline(); }
        });
        builder.service(PlayerQuery.class, new BukkitPlayerQuery());
        builder.service(DelayScheduler.class, duration -> delay(plugin, duration));
        builder.service(ScriptLogger.class, (level, message) -> plugin.getLogger().log(level, message));
        builder.service(ScriptPlatform.class, new BukkitPlatform(scoreboard));
        builder.service(ScriptPropertyAccess.class, new ItemProperties());
        ScriptEngine javascript = javascript(plugin);
        if (javascript != null) builder.service(JavaScriptEvaluator.class, (source, values) -> {
            Bindings bindings = new SimpleBindings(values);
            try { return javascript.eval(source, bindings); }
            catch (ScriptException failure) { throw new IllegalArgumentException(failure.getMessage(), failure); }
        });
        return builder;
    }

    private static CompletableFuture<Object> delay(Plugin plugin, Duration duration) {
        CompletableFuture<Object> future = new CompletableFuture<Object>();
        // 原框架按 50 毫秒一刻向下取整。
        long ticks = Math.max(0L, duration.toMillis() / 50L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> future.complete(null), ticks);
        return future;
    }

    private static ScriptEngine javascript(Plugin plugin) {
        try {
            ScriptEngineManager manager = new ScriptEngineManager(plugin.getClass().getClassLoader());
            ScriptEngine engine = manager.getEngineByName("javascript");
            return engine != null ? engine : manager.getEngineByName("nashorn");
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static CommandSender commandSender(Object sender) {
        return sender == null ? Bukkit.getConsoleSender() : (CommandSender) sender;
    }

    private static Player player(Object sender) {
        if (!(sender instanceof Player)) throw new IllegalStateException("No player selected.");
        return (Player) sender;
    }

    /** 低版本 API 中不存在的方法：反射调用，缺失时报不支持。 */
    static Object invoke(Object target, String method, Object... args) {
        try {
            for (java.lang.reflect.Method candidate : target.getClass().getMethods()) {
                if (candidate.getName().equals(method) && candidate.getParameterTypes().length == args.length) {
                    return candidate.invoke(target, args);
                }
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(method + " failed", failure);
        }
        throw new UnsupportedOperationException(method + " is not supported for this minecraft version");
    }

    private static String name(Object value) {
        if (value == null) return null;
        if (value instanceof Enum<?>) return ((Enum<?>) value).name();
        return String.valueOf(value);
    }

    private static final class BukkitPlayerQuery implements PlayerQuery {
        @Override public boolean hasPermission(Object sender, String permission) { return commandSender(sender).hasPermission(permission); }

        @SuppressWarnings("deprecation")
        @Override public Object property(Object sender, String name) {
            Player p = player(sender);
            Location location = p.getLocation();
            switch (name) {
                case "locale": return invoke(p, "getLocale");
                case "world": return location.getWorld() == null ? null : location.getWorld().getName();
                case "x": return Double.valueOf(location.getX());
                case "y": return Double.valueOf(location.getY());
                case "z": return Double.valueOf(location.getZ());
                case "yaw": return Float.valueOf(location.getYaw());
                case "pitch": return Float.valueOf(location.getPitch());
                case "block x": return Integer.valueOf(location.getBlockX());
                case "block y": return Integer.valueOf(location.getBlockY());
                case "block z": return Integer.valueOf(location.getBlockZ());
                case "compass x": return Integer.valueOf(p.getCompassTarget().getBlockX());
                case "compass y": return Integer.valueOf(p.getCompassTarget().getBlockY());
                case "compass z": return Integer.valueOf(p.getCompassTarget().getBlockZ());
                case "location": return location;
                case "compass target": return p.getCompassTarget();
                case "bed spawn": return p.getBedSpawnLocation();
                case "bed spawn x": return p.getBedSpawnLocation() == null ? null : Integer.valueOf(p.getBedSpawnLocation().getBlockX());
                case "bed spawn y": return p.getBedSpawnLocation() == null ? null : Integer.valueOf(p.getBedSpawnLocation().getBlockY());
                case "bed spawn z": return p.getBedSpawnLocation() == null ? null : Integer.valueOf(p.getBedSpawnLocation().getBlockZ());
                case "name": return p.getName();
                case "list name": return p.getPlayerListName();
                case "display name": case "displayname": return p.getDisplayName();
                case "uuid": return p.getUniqueId().toString();
                case "gamemode": return p.getGameMode().name();
                case "address": {
                    InetSocketAddress address = p.getAddress();
                    return address == null ? null : address.getHostString();
                }
                case "sneaking": return Boolean.valueOf(p.isSneaking());
                case "sprinting": return Boolean.valueOf(p.isSprinting());
                case "blocking": return Boolean.valueOf(p.isBlocking());
                case "gliding": return Boolean.valueOf(p.isGliding());
                case "glowing": return Boolean.valueOf(p.isGlowing());
                case "swimming": return invoke(p, "isSwimming");
                case "riptiding": return invoke(p, "isRiptiding");
                case "sleeping": return Boolean.valueOf(p.isSleeping());
                case "sleep ticks": return Integer.valueOf(p.getSleepTicks());
                case "sleep ignored": return Boolean.valueOf(p.isSleepingIgnored());
                case "dead": return Boolean.valueOf(p.isDead());
                case "conversing": return Boolean.valueOf(p.isConversing());
                case "leashed": return Boolean.valueOf(p.isLeashed());
                case "on ground": return Boolean.valueOf(p.isOnGround());
                case "is online": return Boolean.valueOf(p.isOnline());
                case "inside vehicle": return Boolean.valueOf(p.isInsideVehicle());
                case "op": return Boolean.valueOf(p.isOp());
                case "gravity": return Boolean.valueOf(p.hasGravity());
                case "attack cooldown": return invoke(p, "getAttackCooldown");
                case "player time": return Long.valueOf(p.getPlayerTime());
                case "first played": return Long.valueOf(p.getFirstPlayed());
                case "last played": return Long.valueOf(p.getLastPlayed());
                case "absorption amount": return invoke(p, "getAbsorptionAmount");
                case "no damage ticks": return Integer.valueOf(p.getNoDamageTicks());
                case "remaining air": return Integer.valueOf(p.getRemainingAir());
                case "maximum air": return Integer.valueOf(p.getMaximumAir());
                case "exp": return Float.valueOf(p.getExp());
                case "level": return Integer.valueOf(p.getLevel());
                case "total exp": return Integer.valueOf(p.getTotalExperience());
                case "exhaustion": return Float.valueOf(p.getExhaustion());
                case "saturation": return Float.valueOf(p.getSaturation());
                case "food level": return Integer.valueOf(p.getFoodLevel());
                case "health": return Double.valueOf(p.getHealth());
                case "max health": return Double.valueOf(p.getMaxHealth());
                case "allow flight": return Boolean.valueOf(p.getAllowFlight());
                case "flying": return Boolean.valueOf(p.isFlying());
                case "fly speed": return Float.valueOf(p.getFlySpeed());
                case "walk speed": return Float.valueOf(p.getWalkSpeed());
                case "ping": return invoke(p, "getPing");
                case "pose": return name(invoke(p, "getPose"));
                case "facing": return name(invoke(p, "getFacing"));
                default: return null;
            }
        }

        @SuppressWarnings("deprecation")
        @Override public boolean write(Object sender, String name, Method method, Object value) {
            Player p = player(sender);
            switch (name) {
                case "yaw": case "pitch": {
                    Location location = p.getLocation();
                    if ("yaw".equals(name)) location.setYaw(modify(location.getYaw(), method, value, Float.NaN, Float.NaN));
                    else location.setPitch(modify(location.getPitch(), method, value, Float.NaN, Float.NaN));
                    p.teleport(location);
                    return true;
                }
                case "location": return modifyOnly(method) && p.teleport((Location) value);
                case "compass target": if (!modifyOnly(method)) return false; p.setCompassTarget((Location) value); return true;
                case "bed spawn": if (!modifyOnly(method)) return false; p.setBedSpawnLocation((Location) value); return true;
                case "list name": if (!modifyOnly(method)) return false; p.setPlayerListName(value == null ? null : value.toString()); return true;
                case "display name": if (!modifyOnly(method)) return false; p.setDisplayName(value == null ? null : value.toString()); return true;
                case "gamemode": if (!modifyOnly(method)) return false; p.setGameMode(gameMode(value)); return true;
                case "gliding": if (!modifyOnly(method)) return false; p.setGliding(KetherSupport.bool(value)); return true;
                case "glowing": if (!modifyOnly(method)) return false; p.setGlowing(KetherSupport.bool(value)); return true;
                case "swimming": if (!modifyOnly(method)) return false; invoke(p, "setSwimming", Boolean.valueOf(KetherSupport.bool(value))); return true;
                case "sleep ignored": if (!modifyOnly(method)) return false; p.setSleepingIgnored(KetherSupport.bool(value)); return true;
                case "op": if (!modifyOnly(method)) return false; p.setOp(KetherSupport.bool(value)); return true;
                case "gravity": if (!modifyOnly(method)) return false; p.setGravity(KetherSupport.bool(value)); return true;
                case "allow flight": if (!modifyOnly(method)) return false; p.setAllowFlight(KetherSupport.bool(value)); return true;
                case "flying": if (!modifyOnly(method)) return false; p.setFlying(KetherSupport.bool(value)); return true;
                case "player time": p.setPlayerTime(modify(p.getPlayerTime(), method, value), true); return true;
                case "absorption amount": {
                    double current = ((Number) invoke(p, "getAbsorptionAmount")).doubleValue();
                    invoke(p, "setAbsorptionAmount", Double.valueOf(modify(current, method, value, Double.NaN, 0.0)));
                    return true;
                }
                case "no damage ticks": p.setNoDamageTicks(modify(p.getNoDamageTicks(), method, value, null, 0)); return true;
                case "remaining air": p.setRemainingAir(modify(p.getRemainingAir(), method, value, null, 0)); return true;
                case "exp": p.setExp(modify(p.getExp(), method, value, 1f, 0f)); return true;
                case "level": p.setLevel(modify(p.getLevel(), method, value, null, 0)); return true;
                case "exhaustion": p.setExhaustion(modify(p.getExhaustion(), method, value, Float.NaN, Float.NaN)); return true;
                case "saturation": p.setSaturation(modify(p.getSaturation(), method, value, 20f, 0f)); return true;
                case "food level": p.setFoodLevel(modify(p.getFoodLevel(), method, value, Integer.valueOf(20), Integer.valueOf(0))); return true;
                case "health": p.setHealth(modify(p.getHealth(), method, value, p.getMaxHealth(), 0.0)); return true;
                case "max health": p.setMaxHealth(modify(p.getMaxHealth(), method, value, Double.NaN, 0.0)); return true;
                case "fly speed": p.setFlySpeed(modify(p.getFlySpeed(), method, value, 0.99f, -0.99f)); return true;
                case "walk speed": p.setWalkSpeed(modify(p.getWalkSpeed(), method, value, 0.99f, -0.99f)); return true;
                default: return false;
            }
        }

        private static boolean modifyOnly(Method method) { return method == Method.MODIFY; }

        private static GameMode gameMode(Object value) {
            switch (String.valueOf(value).toUpperCase(Locale.ROOT)) {
                case "SURVIVAL": case "0": return GameMode.SURVIVAL;
                case "CREATIVE": case "1": return GameMode.CREATIVE;
                case "ADVENTURE": case "2": return GameMode.ADVENTURE;
                case "SPECTATOR": case "3": return GameMode.SPECTATOR;
                default: throw new IllegalArgumentException("Unknown GameMode " + value);
            }
        }

        private static int modify(int current, Method method, Object value, Integer max, Integer min) {
            int result = method == Method.INCREASE ? current + KetherSupport.integer(value)
                    : method == Method.DECREASE ? current - KetherSupport.integer(value) : KetherSupport.integer(value);
            if (max != null && result > max) return max;
            return min != null && result < min ? min : result;
        }

        private static long modify(long current, Method method, Object value) {
            return method == Method.INCREASE ? current + KetherSupport.longValue(value)
                    : method == Method.DECREASE ? current - KetherSupport.longValue(value) : KetherSupport.longValue(value);
        }

        private static float modify(float current, Method method, Object value, float max, float min) {
            float delta = (float) KetherSupport.number(value);
            float result = method == Method.INCREASE ? current + delta : method == Method.DECREASE ? current - delta : delta;
            if (!Float.isNaN(max) && result > max) return max;
            return !Float.isNaN(min) && result < min ? min : result;
        }

        private static double modify(double current, Method method, Object value, double max, double min) {
            double delta = KetherSupport.number(value);
            double result = method == Method.INCREASE ? current + delta : method == Method.DECREASE ? current - delta : delta;
            if (!Double.isNaN(max) && result > max) return max;
            return !Double.isNaN(min) && result < min ? min : result;
        }
    }

    private static final class BukkitPlatform implements ScriptPlatform {
        private final BiConsumer<Player, List<String>> scoreboard;

        private BukkitPlatform(BiConsumer<Player, List<String>> scoreboard) { this.scoreboard = scoreboard; }

        @Override public List<String> onlinePlayerNames() {
            List<String> names = new ArrayList<String>();
            for (Player player : Bukkit.getOnlinePlayers()) names.add(player.getName());
            return names;
        }
        @Override public void broadcast(String message) { for (Player player : Bukkit.getOnlinePlayers()) player.sendMessage(message); }
        @Override public Object console() { return Bukkit.getConsoleSender(); }
        @Override public Object player(String name) { return Bukkit.getPlayerExact(name); }
        /** spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(message))，bungee chat 只在运行时存在。 */
        @Override public void actionBar(Object target, String message) {
            Player player = BukkitScriptServices.player(target);
            try {
                ClassLoader loader = player.getClass().getClassLoader();
                Class<?> type = Class.forName("net.md_5.bungee.api.ChatMessageType", true, loader);
                Class<?> text = Class.forName("net.md_5.bungee.api.chat.TextComponent", true, loader);
                Class<?> components = Class.forName("[Lnet.md_5.bungee.api.chat.BaseComponent;", true, loader);
                Object spigot = player.getClass().getMethod("spigot").invoke(player);
                Object barType = type.getField("ACTION_BAR").get(null);
                Object content = text.getMethod("fromLegacyText", String.class).invoke(null, message);
                spigot.getClass().getMethod("sendMessage", type, components).invoke(spigot, barType, content);
            } catch (ReflectiveOperationException failure) {
                throw new UnsupportedOperationException("actionbar is not supported on this server", failure);
            }
        }
        @Override public void title(Object player, String title, String subtitle, int fadeIn, int stay, int fadeOut) {
            BukkitScriptServices.player(player).sendTitle(title, subtitle, fadeIn, stay, fadeOut);
        }
        @Override public void playSound(Object target, String sound, float volume, float pitch) {
            Player player = BukkitScriptServices.player(target);
            if (sound.startsWith("resource:")) player.playSound(player.getLocation(), sound.substring("resource:".length()), volume, pitch);
            else player.playSound(player.getLocation(), (Sound) invokeStatic(Sound.class, "valueOf", sound), volume, pitch);
        }
        @Override public void stopSound(Object target, String sound) {
            Player player = BukkitScriptServices.player(target);
            if (sound.startsWith("resource:")) player.stopSound(sound.substring("resource:".length()));
            else player.stopSound((Sound) invokeStatic(Sound.class, "valueOf", sound));
        }
        @Override public Object location(String world, double x, double y, double z, float yaw, float pitch) {
            World target = Bukkit.getWorld(world);
            return new Location(target, x, y, z, yaw, pitch);
        }
        @Override public Object material(String name) {
            Material material = Material.getMaterial(name.toUpperCase(Locale.ROOT));
            if (material == null) throw new IllegalArgumentException("Unknown material: " + name);
            return material;
        }
        @Override public Object itemStack(String material) { return new ItemStack((Material) material(material)); }
        @Override public void scoreboard(Object player, List<String> lines) {
            if (scoreboard == null) ScriptPlatform.super.scoreboard(player, lines);
            else scoreboard.accept(BukkitScriptServices.player(player), lines);
        }

        /** 高版本 Sound 为接口，枚举 valueOf 不存在时改用静态方法。 */
        private static Object invokeStatic(Class<?> type, String method, String argument) {
            try { return type.getMethod(method, String.class).invoke(null, argument); }
            catch (ReflectiveOperationException failure) { throw new IllegalArgumentException("Unknown sound: " + argument, failure); }
        }
    }

    /** 原框架 ItemStack 与 ItemMeta 属性。 */
    private static final class ItemProperties implements ScriptPropertyAccess {
        @SuppressWarnings("deprecation")
        @Override public Result read(Object instance, String key) {
            if (instance instanceof ItemStack) {
                ItemStack item = (ItemStack) instance;
                switch (key) {
                    case "type": case "material": return Result.supported(item.getType());
                    case "meta": case "itemmeta": return Result.supported(item.getItemMeta());
                    case "data": case "damage": case "durability": return Result.supported(Short.valueOf(item.getDurability()));
                    case "name": return Result.supported(item.getItemMeta() == null ? null : item.getItemMeta().getDisplayName());
                    case "lore": return Result.supported(lore(item.getItemMeta()));
                    default: return Result.unsupported();
                }
            }
            if (instance instanceof ItemMeta) {
                ItemMeta meta = (ItemMeta) instance;
                if ("name".equals(key)) return Result.supported(meta.getDisplayName());
                if ("lore".equals(key)) return Result.supported(lore(meta));
            }
            return Result.unsupported();
        }

        @SuppressWarnings("deprecation")
        @Override public Result write(Object instance, String key, Object value) {
            if (instance instanceof ItemStack) {
                ItemStack item = (ItemStack) instance;
                ItemMeta meta = item.getItemMeta();
                switch (key) {
                    case "type": case "material":
                        if (value instanceof Material) item.setType((Material) value);
                        else {
                            Material material = Material.getMaterial(String.valueOf(value).toUpperCase(Locale.ROOT));
                            if (material == null) throw new IllegalArgumentException("Unknown material: " + value);
                            item.setType(material);
                        }
                        return Result.supported(null);
                    case "meta": case "itemmeta": item.setItemMeta((ItemMeta) value); return Result.supported(null);
                    case "data": case "damage": case "durability": item.setDurability((short) KetherSupport.integer(value)); return Result.supported(null);
                    case "name": if (meta != null) meta.setDisplayName(String.valueOf(value)); item.setItemMeta(meta); return Result.supported(null);
                    case "lore": if (meta != null) meta.setLore(lines(value)); item.setItemMeta(meta); return Result.supported(null);
                    default: return Result.unsupported();
                }
            }
            if (instance instanceof ItemMeta) {
                ItemMeta meta = (ItemMeta) instance;
                if ("name".equals(key)) { meta.setDisplayName(String.valueOf(value)); return Result.supported(null); }
                if ("lore".equals(key)) { meta.setLore(lines(value)); return Result.supported(null); }
            }
            return Result.unsupported();
        }

        private static List<String> lore(ItemMeta meta) {
            return meta == null || meta.getLore() == null ? new ArrayList<String>() : meta.getLore();
        }

        private static List<String> lines(Object value) {
            List<String> lines = new ArrayList<String>();
            if (value != null) for (Object line : KetherSupport.elements(value)) lines.add(String.valueOf(line));
            return lines;
        }
    }

}
