package me.kzheart.klib.script;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import me.kzheart.klib.scope.Disposable;
import me.kzheart.klib.scope.Scope;
import me.kzheart.klib.script.kether.core.SimpleQuestContext;

/**
 * 可选的作用域绑定脚本运行时。跟踪原生上下文及根结果返回后的 async 动作，
 * reload 时可取消当前执行，作用域释放时永久关闭。初始求值仍由调用线程执行；
 * 宿主应在合法的动作线程上开始执行、reload 和关闭，异步续接使用给定执行器。
 * 返回 Future 的 cancel 同步执行原生清理；若自定义 Frame 的 closable 触碰 Bukkit，
 * 也必须在宿主线程取消。运行时不会把任意调用线程上的清理自动切换到主线程。
 */
public final class ScopedScriptRuntime implements ScriptEngine, AutoCloseable {
    private final Object lock = new Object();
    private final Set<Execution> active = new HashSet<Execution>();
    private final Scope scope;
    private final Disposable disposal;
    private final KetherScriptEngine engine;
    private boolean closed;

    /** toleranceParser 仅影响此运行时；既有 KetherScriptEngine 构造器不受影响。 */
    public ScopedScriptRuntime(Scope scope, StatementRegistry registry,
                               UnknownStatementResolver unknownResolver,
                               Executor continuationExecutor, boolean toleranceParser) {
        this.scope = Objects.requireNonNull(scope, "scope");
        this.engine = new KetherScriptEngine(registry, unknownResolver,
                continuationExecutor, toleranceParser, this);
        this.disposal = this::close;
        scope.install(disposal);
    }

    /** 返回同一生命周期绑定的引擎；直接使用它也不能绕过关闭或取消。 */
    public KetherScriptEngine engine() { return engine; }

    /** 仅编译验证，绝不执行脚本中的动作。 */
    public void validate(String script, ScriptContext context) {
        checkOpen();
        engine.validate(script, context);
    }

    @Override
    public CompletionStage<Object> eval(String script, ScriptContext context) {
        checkOpen();
        return engine.eval(script, context);
    }

    /** 编译失败同步抛出；执行阶段支持向原生上下文传播取消。 */
    public CompletionStage<Object> evalChecked(String script, ScriptContext context) {
        checkOpen();
        return engine.evalChecked(script, context);
    }

    @Override
    public CompletionStage<Boolean> evalCondition(String script, ScriptContext context) {
        checkOpen();
        return engine.evalCondition(script, context);
    }

    /** 取消当前执行及脱离根结果的动作；之后仍可执行新脚本，适合配置 reload。 */
    public void cancelPending() {
        ArrayList<Execution> snapshot;
        synchronized (lock) { snapshot = new ArrayList<Execution>(active); }
        for (Execution execution : snapshot) execution.cancel();
    }

    /** 永久关闭。取消直接完成，不向可能已经关闭的宿主 scheduler 投递清理任务。 */
    @Override
    public void close() {
        synchronized (lock) {
            if (closed) return;
            closed = true;
        }
        cancelPending();
        scope.remove(disposal);
    }

    boolean track(SimpleQuestContext context, CompletableFuture<Object> result, CompletableFuture<Void> idle) {
        Execution execution = new Execution(context, result);
        synchronized (lock) {
            if (!closed) {
                active.add(execution);
                idle.whenComplete((value, failure) -> {
                    synchronized (lock) { active.remove(execution); }
                });
                return true;
            }
        }
        execution.cancel();
        return false;
    }

    private void checkOpen() {
        synchronized (lock) {
            if (closed) throw new IllegalStateException("Script runtime is closed");
        }
    }

    int activeExecutionCount() {
        synchronized (lock) { return active.size(); }
    }

    private static final class Execution {
        private final SimpleQuestContext context;
        private final CompletableFuture<Object> result;
        private Execution(SimpleQuestContext context, CompletableFuture<Object> result) {
            this.context = context;
            this.result = result;
        }
        private void cancel() {
            result.cancel(false);
            context.terminate();
        }
    }
}
