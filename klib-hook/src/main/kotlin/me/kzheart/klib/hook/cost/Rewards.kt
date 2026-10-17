package me.kzheart.klib.hook.cost

import java.util.ArrayList
import java.util.Collections
import java.util.LinkedHashMap
import java.util.Locale
import java.util.Objects
import org.bukkit.entity.Player
import org.bukkit.Server

/** 配置条目注册表，解析 type:argument 及可选的 " | 描述"。 */
class Rewards private constructor(types: Map<String, RewardType>) {
    private val types: Map<String, RewardType> = Collections.unmodifiableMap(LinkedHashMap(types))
    fun parse(vararg entries: String): RewardPlan = parse(entries.asList())
    fun parse(entries: Collection<String>): RewardPlan {
        val parsed = ArrayList<Reward>(entries.size)
        entries.forEachIndexed { index, raw ->
            try { parsed.add(parseOne(raw)) } catch (failure: IllegalArgumentException) {
                throw IllegalArgumentException("Invalid reward #${index + 1} '$raw': ${failure.message}", failure)
            }
        }
        return RewardPlan(parsed)
    }
    fun parseOne(raw: String): Reward {
        val entry = Entry.parse(raw)
        val type = types[entry.type] ?: throw IllegalArgumentException("Unknown reward type '${entry.type}', known: ${types.keys}")
        val parsed = Objects.requireNonNull(type.parse(entry.argument), "parsed reward")
        return if (entry.description == null) parsed else Described(parsed, entry.description)
    }
    fun types(): Collection<String> = types.keys

    private class Described(private val delegate: Reward, private val description: String) : Reward {
        override fun describe(): String = description
        override fun grant(player: Player) { delegate.grant(player) }
    }

    class Builder internal constructor() {
        private val types = LinkedHashMap<String, RewardType>()
        fun type(prefix: String, type: RewardType): Builder = apply {
            val key = prefix.trim { it <= ' ' }.lowercase(Locale.ROOT)
            require(key.matches(Regex("[a-z0-9_-]+"))) { "Invalid reward type prefix: '$prefix'" }
            check(types.putIfAbsent(key, type) == null) { "Reward type already registered: $key" }
        }
        fun defaults(server: Server): Builder = type("console", RewardTypes.consoleCommand(server))
            .type("player", RewardTypes.playerCommand()).type("op", RewardTypes.opCommand()).type("msg", RewardTypes.message())
        fun build(): Rewards = Rewards(types)
    }
    companion object { @JvmStatic fun builder(): Builder = Builder() }
}
