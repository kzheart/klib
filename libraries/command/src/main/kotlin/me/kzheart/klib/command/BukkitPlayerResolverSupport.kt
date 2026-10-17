package me.kzheart.klib.command
import java.util.Locale
import org.bukkit.Bukkit
import org.bukkit.entity.Player
internal object BukkitPlayerResolverSupport {
    @JvmStatic fun findExact(name: String): Player? = Bukkit.getPlayerExact(name)
    @JvmStatic fun suggest(prefix: String): List<String> {
        val normalized = prefix.lowercase(Locale.ROOT)
        return Bukkit.getOnlinePlayers().map { it.name }.filter { it.lowercase(Locale.ROOT).startsWith(normalized) }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
    }
}
