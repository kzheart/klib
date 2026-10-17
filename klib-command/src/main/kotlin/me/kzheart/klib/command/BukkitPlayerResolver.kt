package me.kzheart.klib.command
import java.util.Locale
import org.bukkit.Bukkit
import org.bukkit.entity.Player
class BukkitPlayerResolver private constructor() : PlayerResolver {
    override fun findExact(name: String): Player? = Bukkit.getPlayerExact(name)
    override fun suggest(prefix: String): List<String> {
        val normalized = prefix.lowercase(Locale.ROOT)
        return Bukkit.getOnlinePlayers().map { it.name }.filter { it.lowercase(Locale.ROOT).startsWith(normalized) }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
    }
    companion object { @JvmField val INSTANCE = BukkitPlayerResolver() }
}
