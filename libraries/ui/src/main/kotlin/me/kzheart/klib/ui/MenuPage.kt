package me.kzheart.klib.ui

import java.util.function.Consumer
import java.util.function.Function

/** 可重用的页面定义；状态和窗口资源归每次激活的会话所有。 */
class MenuPage<S> private constructor(
    internal val id: String,
    internal val view: Function<in ReactiveMenuSession<S>, MenuModel>,
    internal val onOpen: Consumer<in ReactiveMenuSession<S>>,
    internal val configureWindow: Consumer<in MenuSession>
) {
    class Builder<S> internal constructor(private val id: String, private val view: Function<in ReactiveMenuSession<S>, MenuModel>) {
        private var onOpen: Consumer<in ReactiveMenuSession<S>> = Consumer { }
        private var configureWindow: Consumer<in MenuSession> = Consumer { }
        init { require(id.isNotBlank()) { "menu page id must not be blank" } }
        fun onOpen(action: Consumer<in ReactiveMenuSession<S>>): Builder<S> = apply { onOpen = action }
        fun configureWindow(action: Consumer<in MenuSession>): Builder<S> = apply { configureWindow = action }
        fun build(): MenuPage<S> = MenuPage(id, view, onOpen, configureWindow)
    }
    companion object {
        @JvmStatic fun <S> builder(id: String, view: Function<in ReactiveMenuSession<S>, MenuModel>): Builder<S> = Builder(id, view)
    }
}
