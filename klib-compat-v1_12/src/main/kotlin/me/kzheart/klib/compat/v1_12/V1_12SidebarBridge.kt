/*
 * 数据包侧边栏实现，改写自 TabooLib（https://github.com/TabooLib/taboolib）的
 * module/bukkit-nms/bukkit-nms-stable/src/main/kotlin/taboolib/module/nms/NMSScoreboardImpl.kt：
 * 沿用其“每行一个队伍、前缀承载文本”的做法与 1.12 前后缀拆分规则，用 Java 反射代替 TabooLib 的 NMS
 * 代理与重映射。
 *
 * Copyright (c) 2018 Bkm016
 * Licensed under the MIT License; the full license text is reproduced in THIRD_PARTY_NOTICES.md
 * and META-INF/LICENSE-TabooLib-Scoreboard.txt. Source revision: 0e3a911fc55624075b5c9abd4368cb5b063b022b.
 */
package me.kzheart.klib.compat.v1_12

import me.kzheart.klib.compat.SidebarBridge
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.entity.Player
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Optional

/**
 * 1.12.2 至 1.19.x 的队伍侧边栏。每行的不可见持有者连接前缀与后缀，客户端显示默认红色分数。
 *
 * 版本差异仅在首次使用时解析：1.12 的字符串/分数对象与后续组件/动作分数分别封装，
 * 队伍包的构造器或静态工厂另行选择。调用端只表达侧边栏操作，不参与协议布局判断。
 */
internal class V1_12SidebarBridge : SidebarBridge {
    @Volatile private var resolvedPackets: Packets? = null

    override fun maxLines(): Int = 15

    override fun create(player: Player, objective: String, title: String) {
        val packets = packets(player)
        val handle = packets.objective(objective, title)
        packets.send(player, packets.setObjective(handle, METHOD_ADD))
        packets.send(player, packets.display(handle))
    }

    override fun title(player: Player, objective: String, title: String) {
        val packets = packets(player)
        packets.send(player, packets.setObjective(packets.objective(objective, title), METHOD_CHANGE))
    }

    override fun addLine(player: Player, objective: String, index: Int, text: String) {
        val packets = packets(player)
        val owner = owner(objective, index)
        packets.send(player, packets.team(team(objective, index), text, owner, true))
        packets.send(player, packets.changeScore(objective, owner, index))
    }

    override fun updateLine(player: Player, objective: String, index: Int, text: String) {
        val packets = packets(player)
        packets.send(player, packets.team(team(objective, index), text, null, false))
    }

    override fun removeLine(player: Player, objective: String, index: Int) {
        val packets = packets(player)
        packets.send(player, packets.removeScore(objective, owner(objective, index)))
        packets.send(player, packets.removeTeam(team(objective, index)))
    }

    override fun remove(player: Player, objective: String, lineCount: Int) {
        val packets = packets(player)
        packets.send(player, packets.setObjective(packets.objective(objective, ""), METHOD_REMOVE))
        for (index in 0 until lineCount) packets.send(player, packets.removeTeam(team(objective, index)))
    }

    private fun packets(player: Player): Packets = resolvedPackets ?: synchronized(this) {
        resolvedPackets ?: try {
            Packets(player.javaClass.classLoader).also { resolvedPackets = it }
        } catch (failure: ReflectiveOperationException) {
            throw resolutionFailure(failure)
        } catch (failure: RuntimeException) {
            throw resolutionFailure(failure)
        } catch (failure: LinkageError) {
            throw resolutionFailure(failure)
        }
    }

    private fun resolutionFailure(failure: Throwable) =
        IllegalStateException("Cannot resolve sidebar packets on ${Bukkit.getVersion()}", failure)

    /** 已解析的侧边栏协议；反射与版本选择不会泄漏到上层的操作顺序。 */
    private class Packets(loader: ClassLoader) {
        private val craft = Bukkit.getServer().javaClass.`package`.name
        private val versioned = "net.minecraft.server.${craft.substringAfterLast('.')}."
        private val packetType = type(loader, versioned + "Packet", "net.minecraft.network.protocol.Packet")
        private val objectiveType = type(loader, versioned + "ScoreboardObjective", SCORES + "ScoreboardObjective")
        private val teamType = type(loader, versioned + "ScoreboardTeam", SCORES + "ScoreboardTeam")
        private val scoreType = type(loader, versioned + "PacketPlayOutScoreboardScore", GAME_PACKETS + "PacketPlayOutScoreboardScore")
        private val scoreboard = objectiveType.constructors.firstOrNull { it.parameterCount == 5 }?.let {
            ComponentScoreboard(teamType, scoreType, it,
                Class.forName("$craft.util.CraftChatMessage", true, loader).getMethod("fromString", String::class.java))
        } ?: LegacyScoreboard(objectiveType, teamType, scoreType, type(loader, versioned + "ScoreboardScore"))
        private val setObjective = type(loader, versioned + "PacketPlayOutScoreboardObjective", GAME_PACKETS + "PacketPlayOutScoreboardObjective")
            .getConstructor(objectiveType, Int::class.javaPrimitiveType)
        private val display = type(loader, versioned + "PacketPlayOutScoreboardDisplayObjective", GAME_PACKETS + "PacketPlayOutScoreboardDisplayObjective")
            .getConstructor(Int::class.javaPrimitiveType, objectiveType)
        private val teams = TeamPackets.resolve(
            type(loader, versioned + "PacketPlayOutScoreboardTeam", GAME_PACKETS + "PacketPlayOutScoreboardTeam"), teamType)
        private val sender = PacketSender(packetType)

        fun objective(name: String, title: String): Any = scoreboard.objective(name, title)
        fun setObjective(handle: Any, mode: Int): Any = construct(setObjective, handle, mode)
        fun display(handle: Any): Any = construct(display, 1, handle)
        fun changeScore(objective: String, owner: String, value: Int): Any = scoreboard.changeScore(objective, owner, value)
        fun removeScore(objective: String, owner: String): Any = scoreboard.removeScore(objective, owner)
        fun team(name: String, text: String, owner: String?, add: Boolean): Any =
            teams.change(scoreboard.team(name, text, owner), add)
        fun removeTeam(name: String): Any = teams.remove(scoreboard.emptyTeam(name))
        fun send(player: Player, packet: Any) = sender.send(player, packet)
    }

    /** 字符串与聊天组件的差别同时影响目标、队伍文字和分数，因此由一个完整布局负责。 */
    private abstract class Scoreboard(
        protected val objectiveConstructor: Constructor<*>,
        teamType: Class<*>
    ) {
        protected val scoreboard: Any = objectiveConstructor.parameterTypes[0].getConstructor().newInstance()
        protected val criteria: Any = dummyCriteria(objectiveConstructor.parameterTypes[2])
        private val teamConstructor = teamType.getConstructor(objectiveConstructor.parameterTypes[0], String::class.java)
        private val players = teamType.methods.firstOrNull {
            !Modifier.isStatic(it.modifiers) && it.parameterCount == 0 && it.returnType == Collection::class.java
        } ?: throw NoSuchMethodException("${teamType.name} players")

        abstract fun objective(name: String, title: String): Any
        abstract fun changeScore(objective: String, owner: String, value: Int): Any
        abstract fun removeScore(objective: String, owner: String): Any
        protected abstract fun writeTeamText(team: Any, line: String)

        fun emptyTeam(name: String): Any = construct(teamConstructor, scoreboard, name)

        fun team(name: String, line: String, owner: String?): Any = emptyTeam(name).also { team ->
            writeTeamText(team, line)
            if (owner != null) {
                @Suppress("UNCHECKED_CAST")
                val entries = invoke(players, team) as MutableCollection<String>
                entries.add(owner)
            }
        }
    }

    /** 1.12 的有界字符串与 ScoreboardScore 对象，不含现代布局的空占位字段。 */
    private class LegacyScoreboard(
        objectiveType: Class<*>, teamType: Class<*>, scoreType: Class<*>, scoreEntryType: Class<*>
    ) :
        Scoreboard(constructor(objectiveType, 3), teamType) {
        private val title = objectiveType.getMethod("setDisplayName", String::class.java)
        private val prefix = teamType.getMethod("setPrefix", String::class.java)
        private val suffix = teamType.getMethod("setSuffix", String::class.java)
        private val score = scoreType.getConstructor(scoreEntryType)
        private val scoreEntry = scoreEntryType.getConstructor(
            objectiveConstructor.parameterTypes[0], objectiveType, String::class.java)
        private val scoreValue = scoreEntryType.getMethod("setScore", Int::class.javaPrimitiveType)
        private val removeScore = scoreType.getConstructor(String::class.java, objectiveType)

        override fun objective(name: String, title: String): Any =
            construct(objectiveConstructor, scoreboard, name, criteria).also {
                invoke(this.title, it, truncateLegacy(title, LEGACY_TITLE))
            }

        override fun changeScore(objective: String, owner: String, value: Int): Any {
            val entry = construct(scoreEntry, scoreboard, objective(objective, ""), owner)
            invoke(scoreValue, entry, value)
            return construct(score, entry)
        }

        override fun removeScore(objective: String, owner: String): Any =
            construct(removeScore, owner, objective(objective, ""))

        override fun writeTeamText(team: Any, line: String) {
            val (first, second) = splitLegacy(line)
            invoke(prefix, team, first)
            invoke(suffix, team, second)
        }
    }

    /** 1.13–1.19 的无长度限制组件及动作式分数；混淆后的前缀 setter 在解析时探测。 */
    private class ComponentScoreboard(
        teamType: Class<*>,
        scoreType: Class<*>,
        objective: Constructor<*>,
        private val text: Method
    ) : Scoreboard(objective, teamType) {
        private val renderType = objective.parameterTypes[4].enumConstants[0]
        private val componentType = objective.parameterTypes[3]
        private val score = scoreType.constructors.firstOrNull { constructor ->
            constructor.parameterTypes.let {
                it.size == 4 && it[0].isEnum && it[1] == String::class.java &&
                    it[2] == String::class.java && it[3] == Int::class.javaPrimitiveType
            }
        } ?: throw NoSuchMethodException("${scoreType.name}(Action, String, String, int)")
        private val scoreActions = score.parameterTypes[0].enumConstants
        private val prefix = prefixSetter(teamType)

        override fun objective(name: String, title: String): Any =
            construct(objectiveConstructor, scoreboard, name, criteria, component(title), renderType)

        override fun changeScore(objective: String, owner: String, value: Int): Any =
            construct(score, scoreActions[0], objective, owner, value)

        override fun removeScore(objective: String, owner: String): Any =
            construct(score, scoreActions[1], objective, owner, 0)

        override fun writeTeamText(team: Any, line: String) {
            invoke(prefix, team, component(line))
        }

        private fun component(value: String): Any {
            val parts = invoke(text, null, value) as Array<*>
            return if (parts.isNotEmpty()) parts[0]!! else (invoke(text, null, " ") as Array<*>)[0]!!
        }

        /** 1.17 起多个 setter 签名相同；以实际格式化结果区分前缀、后缀与显示名。 */
        private fun prefixSetter(teamType: Class<*>): Method {
            optionalMethod(teamType, "setPrefix", componentType)?.let { return it }
            val methods = teamType.methods.filter {
                !Modifier.isStatic(it.modifiers) && it.parameterTypes.contentEquals(arrayOf(componentType))
            }
            val formatted = methods.lastOrNull { componentType.isAssignableFrom(it.returnType) }
                ?: throw NoSuchMethodException("${teamType.name} formatted name")
            val setters = methods.filter { it.returnType == Void.TYPE }
            for (setter in setters) {
                val team = emptyTeam("klibprobe")
                setter.invoke(team, component("P"))
                val name = formatted.invoke(team, component("N"))
                if (name.javaClass.getMethod("getString").invoke(name) == "PN") return setter
            }
            throw NoSuchMethodException("${teamType.name} prefix setter among $setters")
        }
    }

    /** 同一队伍数据可通过旧构造器或新静态工厂编码，调用者不必知道模式值的差异。 */
    private abstract class TeamPackets {
        abstract fun change(team: Any, add: Boolean): Any
        abstract fun remove(team: Any): Any

        companion object {
            fun resolve(packetType: Class<*>, teamType: Class<*>): TeamPackets {
                val mode = optionalConstructor(packetType, teamType, Int::class.javaPrimitiveType!!)
                if (mode != null) return object : TeamPackets() {
                    override fun change(team: Any, add: Boolean): Any =
                        construct(mode, team, if (add) METHOD_ADD else METHOD_CHANGE)
                    override fun remove(team: Any): Any = construct(mode, team, METHOD_REMOVE)
                }
                val factories = packetType.methods.filter {
                    Modifier.isStatic(it.modifiers) && it.returnType == packetType &&
                        it.parameterTypes.firstOrNull() == teamType
                }
                val change = factories.lastOrNull {
                    it.parameterTypes.contentEquals(arrayOf(teamType, Boolean::class.javaPrimitiveType))
                }
                val remove = factories.lastOrNull { it.parameterCount == 1 }
                if (change == null || remove == null) {
                    throw NoSuchMethodException("${packetType.name} team packet factories")
                }
                return object : TeamPackets() {
                    override fun change(team: Any, add: Boolean): Any = invoke(change, null, team, add)!!
                    override fun remove(team: Any): Any = invoke(remove, null, team)!!
                }
            }
        }
    }

    /** 发送缓存随具体 CraftPlayer 类型更新，避免将一个实现的句柄方法用于另一个实现。 */
    private class PacketSender(private val packetType: Class<*>) {
        @Volatile private var connection: Connection? = null

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
    }

    private class Connection(
        val playerType: Class<*>, private val handle: Method, private val field: Field, private val sender: Method
    ) {
        fun send(player: Player, packet: Any) {
            sender.invoke(field.get(handle.invoke(player)), packet)
        }

        companion object {
            fun resolve(playerType: Class<*>, packetType: Class<*>): Connection {
                val handle = playerType.getMethod("getHandle")
                val field = generateSequence(handle.returnType) { it.superclass }
                    .flatMap { it.declaredFields.asSequence() }
                    .firstOrNull { !Modifier.isStatic(it.modifiers) && it.type.simpleName == "PlayerConnection" }
                    ?: throw NoSuchFieldException("player connection field on ${handle.returnType.name}")
                field.isAccessible = true
                val candidates = field.type.methods.filter {
                    !Modifier.isStatic(it.modifiers) && it.parameterTypes.contentEquals(arrayOf(packetType)) && it.returnType == Void.TYPE
                }
                val sender = candidates.firstOrNull { it.name == "sendPacket" } ?: candidates.singleOrNull()
                    ?: throw NoSuchMethodException("packet send method on ${field.type.name}, candidates: $candidates")
                return Connection(playerType, handle, field, sender)
            }
        }
    }

    companion object {
        private const val METHOD_ADD = 0
        private const val METHOD_REMOVE = 1
        private const val METHOD_CHANGE = 2
        private const val LEGACY_TEXT = 16
        private const val LEGACY_TITLE = 32
        private const val GAME_PACKETS = "net.minecraft.network.protocol.game."
        private const val SCORES = "net.minecraft.world.scores."

        @JvmStatic fun team(objective: String, index: Int): String = objective + hex(index)

        /** 持有者只有颜色代码，末尾重置防止改变后缀格式，长度不超过 1.12 的 40 字符限制。 */
        @JvmStatic fun owner(objective: String, index: Int): String = buildString {
            val key = Integer.toHexString(objective.hashCode()).padStart(8, '0') + hex(index)
            key.forEach { append(ChatColor.COLOR_CHAR).append(it) }
            append(ChatColor.COLOR_CHAR).append('r')
        }

        /** 保留上游的 UTF-16 字符计数与颜色续接规则；前缀和后缀各至多 16 字符。 */
        @JvmStatic fun splitLegacy(text: String): Array<String> {
            if (text.length <= LEGACY_TEXT) return arrayOf(text, "")
            val boundary = if (text[LEGACY_TEXT - 1] == ChatColor.COLOR_CHAR) LEGACY_TEXT - 1 else LEGACY_TEXT
            val prefix = text.substring(0, boundary)
            val suffix = ChatColor.getLastColors(prefix) + text.substring(boundary)
            return arrayOf(prefix, truncateLegacy(suffix, LEGACY_TEXT))
        }

        /** 仅在实际截断时去掉末尾孤立的 §，不改写本来就在限制内的文本。 */
        @JvmStatic fun truncateLegacy(text: String, limit: Int): String {
            if (text.length <= limit) return text
            val cut = text.substring(0, limit)
            return if (cut[limit - 1] == ChatColor.COLOR_CHAR) cut.substring(0, limit - 1) else cut
        }

        private fun hex(index: Int): String =
            "${Character.forDigit(index shr 4 and 15, 16)}${Character.forDigit(index and 15, 16)}"

        private fun type(loader: ClassLoader, vararg names: String): Class<*> {
            for (name in names) {
                try { return Class.forName(name, true, loader) } catch (_: ClassNotFoundException) { /* 另一种包布局 */ }
            }
            throw ClassNotFoundException(names.joinToString(" / "))
        }

        private fun constructor(type: Class<*>, parameterCount: Int): Constructor<*> =
            type.constructors.firstOrNull { it.parameterCount == parameterCount }
                ?: throw NoSuchMethodException("${type.name} constructor with $parameterCount parameters")

        private fun optionalConstructor(type: Class<*>, vararg parameters: Class<*>): Constructor<*>? =
            try { type.getConstructor(*parameters) } catch (_: NoSuchMethodException) { null }

        private fun optionalMethod(type: Class<*>, name: String, vararg parameters: Class<*>): Method? =
            try { type.getMethod(name, *parameters) } catch (_: NoSuchMethodException) { null }

        /** 1.13 起有名称查询方法；1.12 的接口仅公开准则映射表。 */
        private fun dummyCriteria(type: Class<*>): Any {
            for (method in type.methods) {
                if (Modifier.isStatic(method.modifiers) && method.parameterTypes.contentEquals(arrayOf(String::class.java)) &&
                    method.returnType == Optional::class.java) {
                    val dummy = method.invoke(null, "dummy") as Optional<*>
                    if (dummy.isPresent && type.isInstance(dummy.get())) return dummy.get()
                }
            }
            for (field in type.fields) {
                if (Modifier.isStatic(field.modifiers) && Map::class.java.isAssignableFrom(field.type)) {
                    val dummy = (field.get(null) as Map<*, *>)["dummy"]
                    if (type.isInstance(dummy)) return dummy!!
                }
            }
            throw NoSuchFieldException("${type.name} dummy criteria")
        }

        private fun invoke(method: Method, target: Any?, vararg arguments: Any?): Any? = try {
            method.invoke(target, *arguments)
        } catch (failure: InvocationTargetException) {
            throw IllegalStateException("Cannot invoke $method", failure.cause)
        } catch (failure: ReflectiveOperationException) {
            throw IllegalStateException("Cannot invoke $method", failure)
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
