package me.kzheart.klib.hook.economy

import me.kzheart.klib.hook.Hook
import me.kzheart.klib.hook.Hooks
import org.bukkit.Server
import java.util.function.Supplier

/** 内置经济适配器的可选依赖工厂。 */
class CurrencyHooks private constructor() {
    companion object {
        @JvmStatic fun vault(service: Any?): Hook<Currency> =
            Hooks.orNoop("Vault", Supplier { service?.let { VaultCurrency(it) } }, NoopCurrency("vault"))
        @JvmStatic fun vault(server: Server): Hook<Currency> = VaultDiscovery.discover(server)
        @JvmStatic fun playerPoints(api: Any?): Hook<Currency> =
            Hooks.orNoop("PlayerPoints", Supplier { api?.let { PlayerPointsCurrency(it) } }, NoopCurrency("playerpoints"))
        @JvmStatic fun xConomy(api: Any?): Hook<Currency> =
            Hooks.orNoop("XConomy", Supplier { api?.let { XConomyCurrency(it) } }, NoopCurrency("xconomy"))
    }
}
