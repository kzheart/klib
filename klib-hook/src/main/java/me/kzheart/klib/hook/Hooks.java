package me.kzheart.klib.hook;

import java.util.Objects;
import java.util.function.Supplier;
import me.kzheart.klib.scope.Disposable;

/** 用于构造明确的可用、失败和空操作钩子状态的工厂。 */
public final class Hooks {

    private static final Disposable NOTHING = () -> { };

    private Hooks() {
    }

    public static <T> Hook<T> available(String dependency, T value) {
        return new ResolvedHook<T>(
                dependency,
                value,
                DependencyStatus.AVAILABLE,
                "已挂钩",
                value instanceof Disposable ? (Disposable) value : NOTHING);
    }

    public static <T> Hook<T> orNoop(
            String dependency,
            Supplier<? extends T> detector,
            T noop
    ) {
        Objects.requireNonNull(detector, "detector");
        Objects.requireNonNull(noop, "noop");
        try {
            T detected = detector.get();
            if (detected == null) {
                return new ResolvedHook<T>(
                        dependency,
                        noop,
                        DependencyStatus.NOOP,
                        "未安装，使用空实现",
                        NOTHING);
            }
            return available(dependency, detected);
        } catch (RuntimeException | LinkageError failure) {
            // 软依赖缺失最常见的是 NoClassDefFoundError 等 LinkageError，同样归为 FAILED。
            return new ResolvedHook<T>(
                    dependency,
                    noop,
                    DependencyStatus.FAILED,
                    "初始化失败: " + describe(failure),
                    NOTHING);
        }
    }

    /** LinkageError 常见于经济插件版本不兼容，message 往往是唯一定位线索，必须保留。 */
    private static String describe(Throwable failure) {
        String message = failure.getMessage();
        if (message == null || message.isEmpty()) {
            return failure.getClass().getName();
        }
        return failure.getClass().getName() + ": " + message;
    }
}
