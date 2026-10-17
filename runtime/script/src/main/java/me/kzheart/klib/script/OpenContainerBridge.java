package me.kzheart.klib.script;

import java.util.List;
import java.util.concurrent.CompletionStage;

/** 保留嵌套 Java SPI 的平台类型合同，回退执行由 Kotlin 状态机管理。 */
public final class OpenContainerBridge implements UnknownStatementResolver {
    private final ContainerFallback fallback;

    public OpenContainerBridge(ContainerDiscovery discovery) {
        this.fallback = new ContainerFallback(discovery);
    }

    @Override
    public CompletionStage<Object> resolve(String statement, ScriptContext context) {
        return fallback.resolve(statement, context);
    }

    /** 查找当前服务器的活动协议适配器。 */
    @FunctionalInterface
    public interface ContainerDiscovery {

        List<? extends RemoteStatementSource> discover();
    }

    /** 一个类加载器安全的 TabooLib OpenContainer 适配器。 */
    @FunctionalInterface
    public interface RemoteStatementSource {

        CompletionStage<RemoteResolution> resolve(String statement, ScriptContext context);
    }

    /** 区分未解析语句与已解析的 null 结果。 */
    public static final class RemoteResolution {

        private static final RemoteResolution UNRESOLVED = new RemoteResolution(false, null);

        private final boolean resolved;
        private final Object value;

        private RemoteResolution(boolean resolved, Object value) {
            this.resolved = resolved;
            this.value = value;
        }

        public static RemoteResolution unresolved() {
            return UNRESOLVED;
        }

        public static RemoteResolution resolved(Object value) {
            return new RemoteResolution(true, value);
        }

        public boolean isResolved() {
            return resolved;
        }

        public Object value() {
            return value;
        }
    }
}
