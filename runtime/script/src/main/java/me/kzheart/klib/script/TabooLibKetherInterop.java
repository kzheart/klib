package me.kzheart.klib.script;

import java.util.List;
import java.util.concurrent.CompletionStage;
import me.kzheart.klib.scope.Disposable;
import me.kzheart.klib.scope.Scope;
import me.kzheart.klib.script.kether.core.QuestActionParser;

/** Java SPI boundary for cross-loader consumers; Kotlin owns registration and lifecycle. */
public final class TabooLibKetherInterop implements UnknownStatementResolver, KetherParserResolver, Disposable {
    static final String REMOTE_RESOLVE = "kether_remote_resolve";
    static final String CREATE_FRAME = "kether_create_frame";
    static final String CREATE_EXIT_STATUS = "kether_create_exit_status";
    static final String CREATE_PARSED_ACTION = "kether_create_parsed_action";
    static final String ADD_ACTION = "kether_add_action";
    static final String REMOVE_ACTION = "kether_remove_action";

    private final TabooLibInteropState state;

    TabooLibKetherInterop(StatementRegistry registry, String providerName, ContainerDiscovery discovery) {
        state = new TabooLibInteropState(this, registry, providerName, discovery);
    }
    TabooLibInteropState state() { return state; }

    public static TabooLibKetherInterop install(Scope scope, StatementRegistry registry,
            String providerName, ContainerDiscovery discovery) {
        return TabooLibInteropState.install(scope, registry, providerName, discovery);
    }
    public static TabooLibKetherInterop install(Scope scope, StatementRegistry registry, String providerName) {
        return install(scope, registry, providerName, ReflectiveTabooLibContainers.bukkit(providerName));
    }
    public static OpenResult call(String channel, Object[] data) {
        return TabooLibInteropState.call(channel, data);
    }
    public synchronized void refresh() { state.refresh(); }
    @Override public QuestActionParser parser(String action, List<String> namespaces) {
        return state.parser(action, namespaces);
    }
    @Override public CompletionStage<Object> resolve(String statement, ScriptContext context) {
        return state.resolve();
    }
    @Override public synchronized void dispose() { state.dispose(); }

    /** 可替换的 OpenContainer 发现边界。 */
    public interface ContainerDiscovery {
        List<? extends OpenContainer> discover();
        OpenContainer find(String name);
    }

    /** 不泄漏 TabooLib 类型的容器调用边界。 */
    public interface OpenContainer {
        String name();
        OpenResult call(String channel, Object... data);
    }

    /** 与 TabooLib OpenResult.cast 兼容的返回形状。 */
    public static final class OpenResult {
        private static final OpenResult FAILED = new OpenResult(false, null);
        private final boolean successful;
        private final Object value;

        private OpenResult(boolean successful, Object value) {
            this.successful = successful;
            this.value = value;
        }

        public static OpenResult successful() {
            return new OpenResult(true, null);
        }

        public static OpenResult successful(Object value) {
            return new OpenResult(true, value);
        }

        public static OpenResult failed() {
            return FAILED;
        }

        public boolean isSuccessful() {
            return successful;
        }

        public boolean isFailed() {
            return !successful;
        }

        public Object getValue() {
            return value;
        }
    }

}
