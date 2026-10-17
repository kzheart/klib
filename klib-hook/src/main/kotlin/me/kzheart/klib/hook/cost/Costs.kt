package me.kzheart.klib.hook.cost

import java.util.ArrayList
import java.util.Collections
import java.util.LinkedHashMap
import java.util.Locale
import java.util.Objects
import org.bukkit.entity.Player

/** 配置条目注册表，解析 type:argument 及可选的 " | 描述"。 */
class Costs private constructor(types: Map<String, CostType>) {
    private val types: Map<String, CostType> = Collections.unmodifiableMap(LinkedHashMap(types))
    fun parse(vararg entries: String): CostPlan = parse(entries.asList())
    fun parse(entries: Collection<String>): CostPlan {
        val parsed = ArrayList<Cost>(entries.size)
        entries.forEachIndexed { index, raw ->
            try { parsed.add(parseOne(raw)) } catch (failure: IllegalArgumentException) {
                throw IllegalArgumentException("Invalid cost #${index + 1} '$raw': ${failure.message}", failure)
            }
        }
        return CostPlan(parsed)
    }
    fun parseOne(raw: String): Cost {
        val entry = Entry.parse(raw)
        val type = types[entry.type] ?: throw IllegalArgumentException("Unknown cost type '${entry.type}', known: ${types.keys}")
        val parsed = Objects.requireNonNull(type.parse(entry.argument), "parsed cost")
        return if (entry.description == null) parsed else Described(parsed, entry.description)
    }
    fun types(): Collection<String> = types.keys

    private class Described(private val delegate: Cost, private val description: String) : Cost {
        override fun describe(): String = description
        override fun test(player: Player): Boolean = delegate.test(player)
        override fun take(player: Player): Charge = delegate.take(player)
    }

    class Builder internal constructor() {
        private val types = LinkedHashMap<String, CostType>()
        fun type(prefix: String, type: CostType): Builder = apply {
            val key = prefix.trim { it <= ' ' }.lowercase(Locale.ROOT)
            require(key.matches(Regex("[a-z0-9_-]+"))) { "Invalid cost type prefix: '$prefix'" }
            check(types.putIfAbsent(key, type) == null) { "Cost type already registered: $key" }
        }
        fun defaults(): Builder = type("perm", CostTypes.permission()).type("level", CostTypes.level())
        fun build(): Costs = Costs(types)
    }
    companion object { @JvmStatic fun builder(): Builder = Builder() }
}
