/*
 * 数据包侧边栏实现，改写自 TabooLib（https://github.com/TabooLib/taboolib）的
 * module/bukkit-nms/bukkit-nms-stable/src/main/kotlin/taboolib/module/nms/NMSScoreboardImpl.kt
 * 与 NMSScoreboardImpl26.kt：用 Java 反射代替 TabooLib 的 NMS 代理与重映射。
 *
 * Copyright (c) 2018 Bkm016
 * Licensed under the MIT License; attribution and full license text are in
 * META-INF/LICENSE-TabooLib-Scoreboard.txt. Source revision: 0e3a911fc55624075b5c9abd4368cb5b063b022b.
 */
package me.kzheart.klib.compat.internal

import me.kzheart.klib.compat.SidebarBridge
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Optional

/** Shared packet protocol from 1.20.4 onward; representation differences stay inside the resolved layout. */
open class ModernSidebarBridge : SidebarBridge {
    @Volatile private var layout: Packets? = null
    override fun maxLines(): Int = 15
    override fun create(player: Player, objective: String, title: String) {
        val packets = packets(player)
        val handle = packets.objective(objective, title)
        packets.send(player, construct(packets.setObjective, handle, 0))
        packets.send(player, construct(packets.display, packets.sidebarSlot, handle))
    }
    override fun title(player: Player, objective: String, title: String) {
        val packets = packets(player)
        packets.send(player, construct(packets.setObjective, packets.objective(objective, title), 2))
    }
    override fun addLine(player: Player, objective: String, index: Int, text: String) = updateLine(player, objective, index, text)
    override fun updateLine(player: Player, objective: String, index: Int, text: String) {
        val packets = packets(player)
        packets.send(player, packets.score(owner(objective, index), objective, index, text))
    }
    override fun removeLine(player: Player, objective: String, index: Int) {
        val packets = packets(player)
        packets.send(player, construct(packets.resetScore, owner(objective, index), objective))
    }
    override fun remove(player: Player, objective: String, lineCount: Int) {
        val packets = packets(player)
        packets.send(player, construct(packets.setObjective, packets.objective(objective, ""), 1))
    }
    private fun packets(player: Player): Packets = layout ?: synchronized(this) {
        layout ?: try { Packets(player.javaClass.classLoader).also { layout = it } }
        catch (failure: ReflectiveOperationException) { throw resolutionFailure(failure) }
        catch (failure: RuntimeException) { throw resolutionFailure(failure) }
        catch (failure: LinkageError) { throw resolutionFailure(failure) }
    }
    private fun resolutionFailure(failure: Throwable) = IllegalStateException("Cannot resolve sidebar packets on ${Bukkit.getVersion()}", failure)

    private class Packets(loader: ClassLoader) {
        private val packetType = type(loader, "net.minecraft.network.protocol.Packet")
        private val objectiveType = type(loader, "net.minecraft.world.scores.Objective", "net.minecraft.world.scores.ScoreboardObjective")
        private val objective = objectiveType.constructors.firstOrNull { it.parameterCount == 7 }
            ?: throw NoSuchMethodException("${objectiveType.name} constructor with 7 parameters")
        private val parameters = objective.parameterTypes
        private val scoreboard = parameters[0].getConstructor().newInstance()
        private val criteria = dummyCriteria(parameters[2])
        private val renderType = parameters[4].enumConstants[0]
        private val blankFormat = type(loader, "net.minecraft.network.chat.numbers.BlankFormat").let { type ->
            type.fields.firstOrNull { Modifier.isStatic(it.modifiers) && it.type == type }?.get(null)
                ?: throw NoSuchFieldException("${type.name} singleton")
        }
        val setObjective = type(loader, GAME + "ClientboundSetObjectivePacket", GAME + "PacketPlayOutScoreboardObjective")
            .getConstructor(objectiveType, Int::class.javaPrimitiveType)
        val display = type(loader, GAME + "ClientboundSetDisplayObjectivePacket", GAME + "PacketPlayOutScoreboardDisplayObjective")
            .constructors.firstOrNull { it.parameterTypes.let { p -> p.size == 2 && p[0].isEnum && p[1] == objectiveType } }
            ?: throw NoSuchMethodException("(DisplaySlot, Objective)")
        val sidebarSlot = display.parameterTypes[0].enumConstants.let { values -> values.firstOrNull { (it as Enum<*>).name == "SIDEBAR" } ?: values[1] }
        private val score = type(loader, GAME + "ClientboundSetScorePacket", GAME + "PacketPlayOutScoreboardScore")
            .constructors.firstOrNull { it.parameterTypes.let { p -> p.size == 5 && p[0] == String::class.java && p[1] == String::class.java && p[2] == Int::class.javaPrimitiveType } }
            ?: throw NoSuchMethodException("score packet (String, String, int, text, format)")
        private val optionalScore = score.parameterTypes[3] == Optional::class.java
        val resetScore = type(loader, GAME + "ClientboundResetScorePacket").getConstructor(String::class.java, String::class.java)
        private val chat = Class.forName(Bukkit.getServer().javaClass.`package`.name + ".util.CraftChatMessage", true, loader)
        private val singleText = try { chat.getMethod("fromStringOrEmpty", String::class.java) } catch (_: NoSuchMethodException) { null }
        private val text = singleText ?: chat.getMethod("fromString", String::class.java)
        @Volatile private var connection: Connection? = null
        fun objective(name: String, title: String): Any = construct(objective, scoreboard, name, criteria, component(title), renderType, false, blankFormat)
        fun score(owner: String, objectiveName: String, value: Int, line: String): Any {
            val component = component(line)
            return if (optionalScore) construct(score, owner, objectiveName, value, Optional.of(component), Optional.empty<Any>())
                else construct(score, owner, objectiveName, value, component, null)
        }
        fun send(player: Player, packet: Any) {
            try {
                val current = connection?.takeIf { it.playerType == player.javaClass }
                    ?: Connection.resolve(player.javaClass, packetType).also { connection = it }
                current.send(player, packet)
            } catch (failure: InvocationTargetException) {
                throw IllegalStateException("Cannot send sidebar packet to ${player.name}", failure.cause)
            } catch (failure: ReflectiveOperationException) {
                throw IllegalStateException("Cannot send sidebar packet to ${player.name}", failure)
            }
        }
        private fun component(value: String): Any = try {
            val result = text.invoke(null, value)
            if (singleText != null) result else (result as Array<*>).firstOrNull() ?: (text.invoke(null, " ") as Array<*>)[0]!!
        } catch (failure: ReflectiveOperationException) { throw IllegalStateException("Cannot convert sidebar text", failure) }
    }
    private class Connection(val playerType: Class<*>, private val handle: Method, private val field: Field, private val sender: Method) {
        fun send(player: Player, packet: Any) { sender.invoke(field.get(handle.invoke(player)), packet) }
        companion object {
            fun resolve(playerType: Class<*>, packetType: Class<*>): Connection {
                val handle = playerType.getMethod("getHandle")
                val field = generateSequence(handle.returnType) { it.superclass }.flatMap { it.declaredFields.asSequence() }
                    .firstOrNull { !Modifier.isStatic(it.modifiers) && it.type.simpleName in setOf("ServerGamePacketListenerImpl", "PlayerConnection") }
                    ?: throw NoSuchFieldException("player connection field on ${handle.returnType.name}")
                field.isAccessible = true
                val candidates = field.type.methods.filter { !Modifier.isStatic(it.modifiers) && it.parameterTypes.contentEquals(arrayOf(packetType)) && it.returnType == Void.TYPE }
                val sender = candidates.firstOrNull { it.name == "send" } ?: candidates.singleOrNull()
                    ?: throw NoSuchMethodException("packet send method on ${field.type.name}, candidates: $candidates")
                return Connection(playerType, handle, field, sender)
            }
        }
    }
    companion object {
        private const val GAME = "net.minecraft.network.protocol.game."
        @JvmStatic fun owner(objective: String, index: Int): String = buildString {
            (objective + Character.forDigit(index shr 4 and 15, 16) + Character.forDigit(index and 15, 16)).forEach { append('§').append(it) }
        }
        private fun type(loader: ClassLoader, vararg names: String): Class<*> {
            names.forEach { name -> try { return Class.forName(name, true, loader) } catch (_: ClassNotFoundException) {} }
            throw ClassNotFoundException(names.joinToString(" / "))
        }
        private fun dummyCriteria(type: Class<*>): Any {
            type.methods.filter { Modifier.isStatic(it.modifiers) && it.parameterTypes.contentEquals(arrayOf(String::class.java)) && it.returnType == Optional::class.java }.forEach {
                val dummy = it.invoke(null, "dummy") as Optional<*>
                if (dummy.isPresent && type.isInstance(dummy.get())) return dummy.get()
            }
            return type.getField("DUMMY").get(null)
        }
        private fun construct(constructor: Constructor<*>, vararg arguments: Any?): Any = try {
            constructor.newInstance(*arguments)
        } catch (failure: InvocationTargetException) {
            throw IllegalStateException("Cannot create ${constructor.declaringClass.name}", failure.cause)
        } catch (failure: ReflectiveOperationException) {
            throw IllegalStateException("Cannot create ${constructor.declaringClass.name}", failure)
        }
    }
}
