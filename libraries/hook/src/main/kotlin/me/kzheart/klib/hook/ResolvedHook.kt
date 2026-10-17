package me.kzheart.klib.hook

import me.kzheart.klib.scope.Disposable
import java.util.Objects

/** 可选集成工厂使用的默认钩子实现。 */
class ResolvedHook<T>(dependency: String, value: T, private val status: DependencyStatus,
                      detail: String, private val disposal: Disposable) : Hook<T> {
    private val dependency = Texts.requireText(dependency, "dependency")
    private val value = Objects.requireNonNull(value, "value")
    private val detail = Texts.requireText(detail, "detail")
    private var disposed = false

    override fun dependency(): String = dependency
    override fun available(): Boolean = status == DependencyStatus.AVAILABLE
    override fun value(): T = value
    override fun status(): DependencyStatus = status
    override fun detail(): String = detail

    @Synchronized
    override fun dispose() {
        if (!disposed) {
            disposed = true
            disposal.dispose()
        }
    }
}
