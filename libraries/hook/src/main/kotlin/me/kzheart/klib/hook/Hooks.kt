package me.kzheart.klib.hook

import me.kzheart.klib.scope.Disposable
import java.util.Objects
import java.util.function.Supplier

/** 用于构造明确的可用、失败和空操作钩子状态的工厂。 */
class Hooks private constructor() {
    companion object {
        private val NOTHING = Disposable { }

        @JvmStatic
        fun <T> available(dependency: String, value: T): Hook<T> = ResolvedHook(
            dependency, value, DependencyStatus.AVAILABLE, "已挂钩", value as? Disposable ?: NOTHING)

        @JvmStatic
        fun <T> orNoop(dependency: String, detector: Supplier<out T?>, noop: T): Hook<T> {
            Objects.requireNonNull(noop, "noop")
            return try {
                val detected = detector.get()
                if (detected == null) {
                    ResolvedHook(dependency, noop, DependencyStatus.NOOP, "未安装，使用空实现", NOTHING)
                } else {
                    available(dependency, detected)
                }
            } catch (failure: RuntimeException) {
                failed(dependency, noop, failure)
            } catch (failure: LinkageError) {
                failed(dependency, noop, failure)
            }
        }

        private fun <T> failed(dependency: String, noop: T, failure: Throwable): Hook<T> =
            ResolvedHook(dependency, noop, DependencyStatus.FAILED,
                "初始化失败: " + describe(failure), NOTHING)

        /** 软依赖链接失败的 message 往往是唯一定位线索，必须保留。 */
        private fun describe(failure: Throwable): String {
            val message = failure.message
            return if (message.isNullOrEmpty()) failure.javaClass.name else failure.javaClass.name + ": " + message
        }
    }
}
