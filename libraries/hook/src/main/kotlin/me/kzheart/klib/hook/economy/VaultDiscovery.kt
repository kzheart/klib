package me.kzheart.klib.hook.economy

import me.kzheart.klib.hook.Hook
import me.kzheart.klib.hook.Hooks
import org.bukkit.Server
import java.util.function.Supplier

/** 通过 Bukkit 发现 Vault 的 Economy 提供器，且不链接 Vault API。 */
class VaultDiscovery private constructor() {
    companion object {
        private const val ECONOMY_TYPE = "net.milkbowl.vault.economy.Economy"
        @JvmStatic fun discover(server: Server): Hook<Currency> =
            Hooks.orNoop("Vault", Supplier { findProvider(server) }, NoopCurrency("vault"))

        private fun findProvider(server: Server): Currency? {
            val vault = server.pluginManager.getPlugin("Vault") ?: return null
            if (!vault.isEnabled) return null
            return try {
                val economyType = Class.forName(ECONOMY_TYPE, false, vault.javaClass.classLoader)
                val provider = server.servicesManager.getRegistration(economyType)?.provider ?: return null
                VaultCurrency(provider)
            } catch (failure: ClassNotFoundException) {
                throw IllegalStateException("Vault Economy API class is unavailable", failure)
            }
        }
    }
}
