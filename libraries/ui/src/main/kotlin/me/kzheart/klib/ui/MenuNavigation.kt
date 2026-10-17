package me.kzheart.klib.ui

import java.util.ArrayDeque
import org.bukkit.entity.Player

/** 栈帧只保存页面和不可变状态引用，不保留离开的会话、holder 或 Scope。 */
internal class MenuNavigation(private val renderer: MenuRenderer, private val viewer: Player, private val rootBack: Runnable) {
    private val parents = ArrayDeque<Frame<*>>()
    private var active: ReactiveMenuSession<*>? = null

    fun activate(session: ReactiveMenuSession<*>) { active = session }
    fun release(session: ReactiveMenuSession<*>) {
        if (active === session) { active = null; parents.clear() }
    }
    fun <S, C> push(parent: MenuPage<S>, parentState: S, child: MenuPage<C>, childState: C) {
        parents.addLast(Frame(parent, parentState))
        renderer.openPage(viewer, child, childState, this)
    }
    fun back() {
        if (parents.isEmpty()) rootBack.run()
        else parents.removeLast().open(renderer, viewer, this)
    }
    private class Frame<S>(private val page: MenuPage<S>, private val state: S) {
        fun open(renderer: MenuRenderer, viewer: Player, navigation: MenuNavigation) {
            renderer.openPage(viewer, page, state, navigation)
        }
    }
}
