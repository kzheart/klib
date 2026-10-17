package me.kzheart.klib.ui

import java.util.Collections
import java.util.IdentityHashMap
import me.kzheart.klib.KPlugin
import me.kzheart.klib.component.KContext
import me.kzheart.klib.scope.Disposable
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

/** 菜单服务一次装配；每次打开独占一个控制器实例。 */
class Menus private constructor(private val renderer: MenuRenderer) {
    private val active = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
    fun open(player: Player, controller: Any): MenuHolder {
        check(Bukkit.isPrimaryThread()) { "Opening a menu requires the server main thread" }
        val declaration = AnnotatedMenu(controller)
        synchronized(active) { check(active.add(controller)) { "Menu controller already has an open session" } }
        var holder: MenuHolder? = null
        lateinit var refresh: Runnable
        refresh = Runnable { holder?.takeUnless { it.session().isClosed() }?.refresh(declaration.render(refresh)) }
        try {
            return renderer.open(player, controller.javaClass.simpleName, declaration.render(refresh)) { session ->
                session.scope().install(Disposable { synchronized(active) { active.remove(controller) } })
            }.also { holder = it }
        } catch (failure: RuntimeException) { synchronized(active) { active.remove(controller) }; throw failure }
        catch (failure: Error) { synchronized(active) { active.remove(controller) }; throw failure }
    }
    companion object {
        @JvmStatic fun install(plugin: KPlugin): Menus = install(plugin.context(), plugin)
        @JvmStatic fun install(context: KContext, plugin: Plugin): Menus = Menus(MenuRenderer.install(context.scope(), plugin))
    }
}
