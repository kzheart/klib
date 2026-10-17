package me.kzheart.klib.command

import java.lang.reflect.Proxy
import me.kzheart.klib.lang.RichText
import org.bukkit.Bukkit
import org.bukkit.Server
import org.bukkit.entity.Player
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RemainingPlatformReturnTest {
    private fun text(result: CommandResult): String =
        if (result.message() != null) result.message().plainText() else "missing"
    private fun playerName(name: String): String =
        if (BukkitPlayerResolver.INSTANCE.findExact(name) != null)
            BukkitPlayerResolver.INSTANCE.findExact(name).name else "missing"

    @Test fun resultMessageKeepsPlatformInferenceAndNull() {
        assertEquals("missing", text(CommandResult.success()))
        assertNull(CommandResult.message(CommandResult.Status.FAILED, null).message())
        val result = CommandResult.message(CommandResult.Status.HELP, RichText.plain("help"))
        val message: RichText = result.message()
        assertEquals("help", message.plainText())
        assertEquals("help", text(result))
    }

    @Test fun bukkitResolverKeepsAbsentPlayersAndKotlinSuggestionAlgorithm() {
        fun player(name: String): Player = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Player::class.java)) {
            _, method, _ -> if (method.name == "getName") name else null
        } as Player
        val alpha = player("Alpha")
        val alex = player("alex")
        val server = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Server::class.java)) { _, method, args ->
            when (method.name) {
                "getPlayerExact" -> if (args!![0] == "Alpha") alpha else null
                "getOnlinePlayers" -> listOf(alpha, player("Beta"), alex)
                else -> null
            }
        } as Server
        val field = Bukkit::class.java.getDeclaredField("server").apply { isAccessible = true }
        val original = field.get(null)
        try {
            field.set(null, server)
            assertEquals("missing", playerName("absent"))
            assertNull(BukkitPlayerResolver.INSTANCE.findExact("absent"))
            val present: Player = BukkitPlayerResolver.INSTANCE.findExact("Alpha")
            assertSame(alpha, present)
            assertEquals("Alpha", playerName("Alpha"))
            assertEquals(listOf("alex", "Alpha"), BukkitPlayerResolver.INSTANCE.suggest("AL"))
        } finally { field.set(null, original) }
    }
}
