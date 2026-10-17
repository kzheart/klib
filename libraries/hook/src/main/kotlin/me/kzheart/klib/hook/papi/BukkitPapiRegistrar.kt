package me.kzheart.klib.hook.papi

import java.util.concurrent.atomic.AtomicBoolean
import me.clip.placeholderapi.expansion.PlaceholderExpansion
import me.kzheart.klib.hook.Texts
import me.kzheart.klib.scope.Disposable
import org.bukkit.OfflinePlayer
import org.bukkit.plugin.Plugin

/** 将 klib 占位符解析器注册为真正的 PlaceholderAPI 扩展。 */
class BukkitPapiRegistrar internal constructor(author: String, version: String, private val persist: Boolean,
                                               private val lifecycle: ExpansionLifecycle) : PapiRegistrar {
    private val author = Texts.requireText(author, "author")
    private val version = Texts.requireText(version, "version")

    constructor(plugin: Plugin) : this(plugin, false)
    constructor(plugin: Plugin, persist: Boolean) : this(plugin.name, plugin.description.version, persist, DirectExpansionLifecycle())
    constructor(author: String, version: String) : this(author, version, false)
    /** persist 控制扩展能否在 PlaceholderAPI 重载后保留；默认为 false。 */
    constructor(author: String, version: String, persist: Boolean) : this(author, version, persist, DirectExpansionLifecycle())
    internal constructor(author: String, version: String, lifecycle: ExpansionLifecycle) : this(author, version, false, lifecycle)

    override fun register(identifier: String, expansion: PapiExpansion): Disposable {
        val managed = ManagedExpansion(Texts.requireText(identifier, "identifier"), author, version, persist, expansion)
        check(lifecycle.register(managed)) { "PlaceholderAPI rejected expansion: ${managed.identifier}" }
        val disposed = AtomicBoolean()
        return Disposable { if (disposed.compareAndSet(false, true)) lifecycle.unregister(managed) }
    }

    interface ExpansionLifecycle {
        fun register(expansion: Any): Boolean
        fun unregister(expansion: Any)
    }

    private class DirectExpansionLifecycle : ExpansionLifecycle {
        override fun register(expansion: Any): Boolean = (expansion as PlaceholderExpansion).register()
        override fun unregister(expansion: Any) { (expansion as PlaceholderExpansion).unregister() }
    }

    private class ManagedExpansion(private val identifier: String, private val author: String,
            private val version: String, private val persist: Boolean,
            private val expansion: PapiExpansion) : PlaceholderExpansion() {
        override fun getIdentifier(): String = identifier
        override fun getAuthor(): String = author
        override fun getVersion(): String = version
        override fun persist(): Boolean = persist
        override fun onRequest(player: OfflinePlayer?, parameters: String): String? = expansion.resolve(player, parameters)
    }
}
