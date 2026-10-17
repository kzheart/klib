package me.kzheart.klib.remote;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/** Operation 平台返回契约；状态、快照与线程上下文传播均由 Kotlin 实现。 */
public final class RemoteOperation implements AutoCloseable {
    private final RemoteOperationState state;
    private RemoteOperation(RemoteOperationState state) { this.state = state; }
    public static RemoteOperation start(String name) { return new RemoteOperation(RemoteOperationState.start(name)); }
    public static Context current() { return RemoteOperationState.current(); }
    public static Runnable wrapCurrent(Runnable task) { return RemoteOperationState.wrapCurrent(task); }
    public static <T> Callable<T> wrapCurrent(Callable<T> task) { return RemoteOperationState.wrapCurrent(task); }
    public static <T> Supplier<T> wrapCurrent(Supplier<T> task) { return RemoteOperationState.wrapCurrent(task); }
    public RemoteOperation phase(String value) { state.phase(value); return this; }
    public RemoteOperation outcome(String value) { state.outcome(value); return this; }
    public RemoteOperation attribute(String key, String value) { state.attribute(key, value); return this; }
    public Context context() { return state.context(); }
    @Override public void close() { state.close(); }

    public static final class Context {
        final RemoteOperationState.ContextState state;
        Context(RemoteOperationState.ContextState state) { this.state = state; }
        public String id() { return state.id(); }
        public String parentId() { return state.parentId(); }
        public String name() { return state.name(); }
        public String phase() { return state.phase(); }
        public String outcome() { return state.outcome(); }
        public Map<String, Object> toMap() { return state.toMap(); }
    }
}
