/* Player operator semantics: Copyright (c) 2018 Bkm016, MIT. See THIRD_PARTY_NOTICES.md. */
package me.kzheart.klib.script

import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.plugin.Plugin
import java.time.Duration
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.function.BiConsumer
import javax.script.ScriptEngine
import javax.script.ScriptEngineManager
import javax.script.ScriptException
import javax.script.SimpleBindings

/** Bukkit 默认服务；兼容版本反射、玩家属性和物品属性各自封装。 */
class BukkitScriptServices private constructor() {
    companion object {
        @JvmStatic fun apply(builder: ScriptContext.Builder, plugin: Plugin): ScriptContext.Builder = apply(builder, plugin, null)
        @JvmStatic fun apply(builder: ScriptContext.Builder, plugin: Plugin, scoreboard: BiConsumer<Player, List<String>?>?): ScriptContext.Builder =
            apply(builder, plugin, scoreboard, true)
        @JvmStatic fun apply(builder: ScriptContext.Builder, plugin: Plugin, scoreboard: BiConsumer<Player, List<String>?>?, discoverJavascript: Boolean): ScriptContext.Builder {
            builder.service(MessageSink::class.java, MessageSink { sender, message -> commandSender(sender).sendMessage(message) })
            builder.service(CommandSink::class.java, object : CommandSink {
                override fun dispatch(sender: Any?, command: String): Any = Bukkit.dispatchCommand(commandSender(sender), command)
                override fun dispatchAsOperator(sender: Any?, command: String): Any {
                    val player = player(sender)
                    val op = player.isOp
                    player.isOp = true
                    try { return Bukkit.dispatchCommand(player, command) } finally { player.isOp = op }
                }
            })
            builder.service(ScriptSenderQuery::class.java, object : ScriptSenderQuery {
                override fun isPlayer(sender: Any?): Boolean = sender is Player
                override fun name(sender: Any?): String = commandSender(sender).name
                override fun isOnline(sender: Any?): Boolean = sender !is Player || sender.isOnline
            })
            builder.service(PlayerQuery::class.java, BukkitPlayerQuery())
            builder.service(DelayScheduler::class.java, DelayScheduler { delay(plugin, it) })
            builder.service(ScriptLogger::class.java, ScriptLogger { level, message -> plugin.logger.log(level, message) })
            builder.service(ScriptPlatform::class.java, BukkitPlatform(scoreboard))
            builder.service(ScriptPropertyAccess::class.java, ItemProperties())
            val javascript = if (discoverJavascript) javascript(plugin) else null
            if (javascript != null) builder.service(JavaScriptEvaluator::class.java, JavaScriptEvaluator { source, values ->
                try { javascript.eval(source, SimpleBindings(values)) } catch (failure: ScriptException) { throw IllegalArgumentException(failure.message, failure) }
            })
            return builder
        }
        private fun delay(plugin: Plugin, duration: Duration): CompletableFuture<Any?> {
            val future = CompletableFuture<Any?>()
            Bukkit.getScheduler().runTaskLater(plugin, Runnable { future.complete(null) }, maxOf(0L, duration.toMillis() / 50L))
            return future
        }
        private fun javascript(plugin: Plugin): ScriptEngine? = try {
            val manager = ScriptEngineManager(plugin.javaClass.classLoader)
            manager.getEngineByName("javascript") ?: manager.getEngineByName("nashorn")
        } catch (_: RuntimeException) { null } catch (_: LinkageError) { null }
        private fun commandSender(sender: Any?): CommandSender = if (sender == null) Bukkit.getConsoleSender() else sender as CommandSender
        private fun player(sender: Any?): Player = sender as? Player ?: throw IllegalStateException("No player selected.")
        @JvmStatic fun invoke(target: Any, method: String, vararg args: Any?): Any? {
            try {
                for (candidate in target.javaClass.methods) if (candidate.name == method && candidate.parameterTypes.size == args.size) {
                    candidate.isAccessible = true
                    return candidate.invoke(target, *args)
                }
            } catch (failure: ReflectiveOperationException) { throw IllegalStateException("$method failed", failure) }
            throw UnsupportedOperationException("$method is not supported for this minecraft version")
        }
        private fun enumName(value: Any?): String? = if (value is Enum<*>) value.name else value?.toString()
    }

    @Suppress("DEPRECATION")
    private class BukkitPlayerQuery : PlayerQuery {
        override fun hasPermission(sender: Any?, permission: String): Boolean = commandSender(sender).hasPermission(permission)
        override fun property(sender: Any?, name: String): Any? {
            val p = player(sender)
            val location = p.location
            return when (name) {
                "locale" -> invoke(p, "getLocale")
                "world" -> location.world?.name
                "x" -> location.x
                "y" -> location.y
                "z" -> location.z
                "yaw" -> location.yaw
                "pitch" -> location.pitch
                "block x" -> location.blockX
                "block y" -> location.blockY
                "block z" -> location.blockZ
                "compass x" -> p.compassTarget.blockX
                "compass y" -> p.compassTarget.blockY
                "compass z" -> p.compassTarget.blockZ
                "location" -> location
                "compass target" -> p.compassTarget
                "bed spawn" -> p.bedSpawnLocation
                "bed spawn x" -> p.bedSpawnLocation?.blockX
                "bed spawn y" -> p.bedSpawnLocation?.blockY
                "bed spawn z" -> p.bedSpawnLocation?.blockZ
                "name" -> p.name
                "list name" -> p.playerListName
                "display name", "displayname" -> p.displayName
                "uuid" -> p.uniqueId.toString()
                "gamemode" -> p.gameMode.name
                "address" -> p.address?.hostString
                "sneaking" -> p.isSneaking
                "sprinting" -> p.isSprinting
                "blocking" -> p.isBlocking
                "gliding" -> p.isGliding
                "glowing" -> p.isGlowing
                "swimming" -> invoke(p, "isSwimming")
                "riptiding" -> invoke(p, "isRiptiding")
                "sleeping" -> p.isSleeping
                "sleep ticks" -> p.sleepTicks
                "sleep ignored" -> p.isSleepingIgnored
                "dead" -> p.isDead
                "conversing" -> p.isConversing
                "leashed" -> p.isLeashed
                "on ground" -> p.isOnGround
                "is online" -> p.isOnline
                "inside vehicle" -> p.isInsideVehicle
                "op" -> p.isOp
                "gravity" -> p.hasGravity()
                "attack cooldown" -> invoke(p, "getAttackCooldown")
                "player time" -> p.playerTime
                "first played" -> p.firstPlayed
                "last played" -> p.lastPlayed
                "absorption amount" -> invoke(p, "getAbsorptionAmount")
                "no damage ticks" -> p.noDamageTicks
                "remaining air" -> p.remainingAir
                "maximum air" -> p.maximumAir
                "exp" -> p.exp
                "level" -> p.level
                "total exp" -> p.totalExperience
                "exhaustion" -> p.exhaustion
                "saturation" -> p.saturation
                "food level" -> p.foodLevel
                "health" -> p.health
                "max health" -> p.maxHealth
                "allow flight" -> p.allowFlight
                "flying" -> p.isFlying
                "fly speed" -> p.flySpeed
                "walk speed" -> p.walkSpeed
                "ping" -> invoke(p, "getPing")
                "pose" -> enumName(invoke(p, "getPose"))
                "facing" -> enumName(invoke(p, "getFacing"))
                else -> null
            }
        }
        override fun write(sender: Any?, name: String, method: PlayerQuery.Method, value: Any?): Boolean {
            val p = player(sender)
            when (name) {
                "yaw", "pitch" -> {
                    val location = p.location
                    if (name == "yaw") location.yaw = modify(location.yaw, method, value) else location.pitch = modify(location.pitch, method, value)
                    p.teleport(location)
                }
                "location" -> return method == PlayerQuery.Method.MODIFY && p.teleport(value as Location)
                "compass target" -> return modifyOnly(method) { p.compassTarget = value as Location }
                "bed spawn" -> return modifyOnly(method) { p.bedSpawnLocation = value as Location? }
                "list name" -> return modifyOnly(method) { p.setPlayerListName(value?.toString()) }
                "display name" -> return modifyOnly(method) { p.setDisplayName(value?.toString()) }
                "gamemode" -> return modifyOnly(method) { p.gameMode = gameMode(value) }
                "gliding" -> return modifyOnly(method) { p.isGliding = KetherSupport.bool(value) }
                "glowing" -> return modifyOnly(method) { p.isGlowing = KetherSupport.bool(value) }
                "swimming" -> return modifyOnly(method) { invoke(p, "setSwimming", KetherSupport.bool(value)) }
                "sleep ignored" -> return modifyOnly(method) { p.isSleepingIgnored = KetherSupport.bool(value) }
                "op" -> return modifyOnly(method) { p.isOp = KetherSupport.bool(value) }
                "gravity" -> return modifyOnly(method) { p.setGravity(KetherSupport.bool(value)) }
                "allow flight" -> return modifyOnly(method) { p.allowFlight = KetherSupport.bool(value) }
                "flying" -> return modifyOnly(method) { p.isFlying = KetherSupport.bool(value) }
                "player time" -> {
                    val delta = KetherSupport.longValue(value)
                    p.setPlayerTime(when (method) { PlayerQuery.Method.INCREASE -> p.playerTime + delta; PlayerQuery.Method.DECREASE -> p.playerTime - delta; else -> delta }, true)
                }
                "absorption amount" -> invoke(p, "setAbsorptionAmount", modify((invoke(p, "getAbsorptionAmount") as Number).toDouble(), method, value, min = 0.0))
                "no damage ticks" -> p.noDamageTicks = modify(p.noDamageTicks, method, value, min = 0)
                "remaining air" -> p.remainingAir = modify(p.remainingAir, method, value, min = 0)
                "exp" -> p.exp = modify(p.exp, method, value, max = 1f, min = 0f)
                "level" -> p.level = modify(p.level, method, value, min = 0)
                "exhaustion" -> p.exhaustion = modify(p.exhaustion, method, value)
                "saturation" -> p.saturation = modify(p.saturation, method, value, max = 20f, min = 0f)
                "food level" -> p.foodLevel = modify(p.foodLevel, method, value, max = 20, min = 0)
                "health" -> p.health = modify(p.health, method, value, max = p.maxHealth, min = 0.0)
                "max health" -> p.maxHealth = modify(p.maxHealth, method, value, min = 0.0)
                "fly speed" -> p.flySpeed = modify(p.flySpeed, method, value, max = 0.99f, min = -0.99f)
                "walk speed" -> p.walkSpeed = modify(p.walkSpeed, method, value, max = 0.99f, min = -0.99f)
                else -> return false
            }
            return true
        }
        private inline fun modifyOnly(method: PlayerQuery.Method, update: () -> Unit): Boolean {
            if (method != PlayerQuery.Method.MODIFY) return false
            update(); return true
        }
        private fun gameMode(value: Any?): GameMode = when (value.toString().uppercase(Locale.ROOT)) {
            "SURVIVAL", "0" -> GameMode.SURVIVAL
            "CREATIVE", "1" -> GameMode.CREATIVE
            "ADVENTURE", "2" -> GameMode.ADVENTURE
            "SPECTATOR", "3" -> GameMode.SPECTATOR
            else -> throw IllegalArgumentException("Unknown GameMode $value")
        }
        private fun modify(current: Int, method: PlayerQuery.Method, value: Any?, max: Int? = null, min: Int? = null): Int {
            val delta = KetherSupport.integer(value)
            val result = when (method) { PlayerQuery.Method.INCREASE -> current + delta; PlayerQuery.Method.DECREASE -> current - delta; else -> delta }
            return if (max != null && result > max) max else if (min != null && result < min) min else result
        }
        private fun modify(current: Float, method: PlayerQuery.Method, value: Any?, max: Float? = null, min: Float? = null): Float {
            val delta = KetherSupport.number(value).toFloat()
            val result = when (method) { PlayerQuery.Method.INCREASE -> current + delta; PlayerQuery.Method.DECREASE -> current - delta; else -> delta }
            return if (max != null && result > max) max else if (min != null && result < min) min else result
        }
        private fun modify(current: Double, method: PlayerQuery.Method, value: Any?, max: Double? = null, min: Double? = null): Double {
            val delta = KetherSupport.number(value)
            val result = when (method) { PlayerQuery.Method.INCREASE -> current + delta; PlayerQuery.Method.DECREASE -> current - delta; else -> delta }
            return if (max != null && result > max) max else if (min != null && result < min) min else result
        }
    }

    private class BukkitPlatform(private val scoreboard: BiConsumer<Player, List<String>?>?) : ScriptPlatform {
        override fun onlinePlayerNames(): List<String> = Bukkit.getOnlinePlayers().map { it.name }
        override fun broadcast(message: String) { Bukkit.getOnlinePlayers().forEach { it.sendMessage(message) } }
        override fun console(): Any? = Bukkit.getConsoleSender()
        override fun player(name: String): Any? = Bukkit.getPlayerExact(name)
        override fun actionBar(target: Any?, message: String) {
            val player = BukkitScriptServices.player(target)
            try {
                val loader = player.javaClass.classLoader
                val type = Class.forName("net.md_5.bungee.api.ChatMessageType", true, loader)
                val text = Class.forName("net.md_5.bungee.api.chat.TextComponent", true, loader)
                val components = Class.forName("[Lnet.md_5.bungee.api.chat.BaseComponent;", true, loader)
                val spigot = Player::class.java.getMethod("spigot").invoke(player)
                val barType = type.getField("ACTION_BAR").get(null)
                val content = text.getMethod("fromLegacyText", String::class.java).invoke(null, message)
                Player.Spigot::class.java.getMethod("sendMessage", type, components).invoke(spigot, barType, content)
            } catch (failure: ReflectiveOperationException) { throw UnsupportedOperationException("actionbar is not supported on this server", failure) }
        }
        override fun title(player: Any?, title: String, subtitle: String, fadeIn: Int, stay: Int, fadeOut: Int) {
            BukkitScriptServices.player(player).sendTitle(title, subtitle, fadeIn, stay, fadeOut)
        }
        override fun playSound(target: Any?, sound: String, volume: Float, pitch: Float) {
            val player = BukkitScriptServices.player(target)
            if (sound.startsWith("resource:")) player.playSound(player.location, sound.substring("resource:".length), volume, pitch)
            else player.playSound(player.location, invokeStatic(Sound::class.java, "valueOf", sound) as Sound, volume, pitch)
        }
        override fun stopSound(target: Any?, sound: String) {
            val player = BukkitScriptServices.player(target)
            if (sound.startsWith("resource:")) player.stopSound(sound.substring("resource:".length))
            else player.stopSound(invokeStatic(Sound::class.java, "valueOf", sound) as Sound)
        }
        override fun location(world: String, x: Double, y: Double, z: Double, yaw: Float, pitch: Float): Any = Location(Bukkit.getWorld(world), x, y, z, yaw, pitch)
        override fun material(name: String): Any = Material.getMaterial(name.uppercase(Locale.ROOT)) ?: throw IllegalArgumentException("Unknown material: $name")
        override fun itemStack(material: String): Any = ItemStack(material(material) as Material)
        override fun scoreboard(player: Any?, lines: List<String>?) {
            if (scoreboard == null) super<ScriptPlatform>.scoreboard(player, lines) else scoreboard.accept(BukkitScriptServices.player(player), lines)
        }
        private fun invokeStatic(type: Class<*>, method: String, argument: String): Any? = try { type.getMethod(method, String::class.java).invoke(null, argument) }
            catch (failure: ReflectiveOperationException) { throw IllegalArgumentException("Unknown sound: $argument", failure) }
    }

    @Suppress("DEPRECATION")
    private class ItemProperties : ScriptPropertyAccess {
        override fun read(instance: Any?, key: String): ScriptPropertyAccess.Result {
            val value: Any? = when (instance) {
                is ItemStack -> when (key) {
                    "type", "material" -> instance.type
                    "meta", "itemmeta" -> instance.itemMeta
                    "data", "damage", "durability" -> instance.durability
                    "name" -> instance.itemMeta?.displayName
                    "lore" -> lore(instance.itemMeta)
                    else -> return ScriptPropertyAccess.Result.unsupported()
                }
                is ItemMeta -> when (key) { "name" -> instance.displayName; "lore" -> lore(instance); else -> return ScriptPropertyAccess.Result.unsupported() }
                else -> return ScriptPropertyAccess.Result.unsupported()
            }
            return ScriptPropertyAccess.Result.supported(value)
        }
        override fun write(instance: Any?, key: String, value: Any?): ScriptPropertyAccess.Result {
            when (instance) {
                is ItemStack -> {
                    val meta = instance.itemMeta
                    when (key) {
                        "type", "material" -> instance.type = value as? Material ?: Material.getMaterial(value.toString().uppercase(Locale.ROOT))
                            ?: throw IllegalArgumentException("Unknown material: $value")
                        "meta", "itemmeta" -> instance.itemMeta = value as ItemMeta?
                        "data", "damage", "durability" -> instance.durability = KetherSupport.integer(value).toShort()
                        "name" -> { meta?.setDisplayName(value.toString()); instance.itemMeta = meta }
                        "lore" -> { meta?.setLore(lines(value)); instance.itemMeta = meta }
                        else -> return ScriptPropertyAccess.Result.unsupported()
                    }
                }
                is ItemMeta -> when (key) {
                    "name" -> instance.setDisplayName(value.toString())
                    "lore" -> instance.lore = lines(value)
                    else -> return ScriptPropertyAccess.Result.unsupported()
                }
                else -> return ScriptPropertyAccess.Result.unsupported()
            }
            return ScriptPropertyAccess.Result.supported(null)
        }
        private fun lore(meta: ItemMeta?): List<String> = meta?.lore ?: ArrayList()
        private fun lines(value: Any?): List<String> = if (value == null) ArrayList() else KetherSupport.elements(value).map { it.toString() }
    }
}
