package me.kzheart.klib.hook

import me.kzheart.klib.scope.Disposable

/** 已检测到的可选依赖及其适配器公开的值。 */
interface Hook<T> : Disposable {
    fun dependency(): String
    fun available(): Boolean
    fun value(): T
    fun status(): DependencyStatus
    fun detail(): String
}
