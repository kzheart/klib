package me.kzheart.klib.scheduler

import me.kzheart.klib.KLogger
import me.kzheart.klib.scope.Disposable
import me.kzheart.klib.scope.Scope
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitScheduler

class BukkitSchedulerFactory(private val plugin: Plugin, private val scheduler: BukkitScheduler, private val logger: KLogger) : SchedulerFactory {
    private val adapters = HashMap<Scope, KScheduler>()
    override fun forScope(scope: Scope): KScheduler {
        synchronized(adapters) { adapters[scope]?.let { return it } }
        val created = BukkitSchedulerAdapter(plugin, scope, scheduler, logger)
        synchronized(adapters) {
            adapters[scope]?.let { return it }
            adapters[scope] = created
        }
        // Never acquire the scope lock while holding the adapter cache lock.
        try { scope.install(Disposable { synchronized(adapters) { adapters.remove(scope) } }) }
        catch (failure: RuntimeException) { synchronized(adapters) { adapters.remove(scope) }; throw failure }
        return created
    }
}
