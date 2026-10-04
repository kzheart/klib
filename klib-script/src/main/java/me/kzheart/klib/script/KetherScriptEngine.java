package me.kzheart.klib.script;

import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import me.kzheart.klib.script.kether.core.LoadError;
import me.kzheart.klib.script.kether.core.LocalizedException;

/** 由引入的 Kether Quest/Frame 运行时支持的 Java 8 脚本引擎。 */
public final class KetherScriptEngine implements ScriptEngine {

    private static final Executor EXPLICIT_EXECUTOR_REQUIRED = command -> {
        throw new ContinuationExecutorRequiredException();
    };

    private final CoreScriptRuntime runtime;

    /**
     * 创建同步动作引擎。除非通过三参数构造器提供显式续接执行器，否则异步动作会快速失败。
     */
    public KetherScriptEngine(StatementRegistry registry) {
        this(registry, null);
    }

    /**
     * 创建带未知语句解析器的同步动作引擎。异步动作需要使用三参数构造器。
     */
    public KetherScriptEngine(
            StatementRegistry registry,
            UnknownStatementResolver unknownResolver
    ) {
        this(registry, unknownResolver, EXPLICIT_EXECUTOR_REQUIRED);
    }

    /**
     * 创建通过 {@code continuationExecutor} 派发异步动作续接的引擎，
     * 例如使用 Bukkit 主线程调度器。
     */
    public KetherScriptEngine(
            StatementRegistry registry,
            UnknownStatementResolver unknownResolver,
            Executor continuationExecutor
    ) {
        this(registry, unknownResolver, continuationExecutor, false);
    }

    /**
     * 与三参数构造器相同；{@code toleranceParser} 为 true 时与原框架默认行为一致，
     * 未注册的词元按字面量处理（如 {@code array [ 1 2 ]}、{@code if true then 1}），而不是报未知语句。
     */
    public KetherScriptEngine(
            StatementRegistry registry,
            UnknownStatementResolver unknownResolver,
            Executor continuationExecutor,
            boolean toleranceParser
    ) {
        this(registry, unknownResolver, continuationExecutor, toleranceParser, null);
    }

    KetherScriptEngine(StatementRegistry registry, UnknownStatementResolver unknownResolver,
                       Executor continuationExecutor, boolean toleranceParser, ScopedScriptRuntime owner) {
        Objects.requireNonNull(registry, "registry");
        this.runtime = new CoreScriptRuntime(
                registry,
                unknownResolver,
                Objects.requireNonNull(continuationExecutor, "continuationExecutor"),
                toleranceParser, owner);
        BuiltInStatements.install(registry);
    }

    /**
     * 仅编译并验证全部任务块，不创建执行上下文，也不运行任何动作或宿主服务。
     * 与 eval / evalChecked 共用编译缓存；编译失败同步抛出 ScriptException。
     */
    public void validate(String script, ScriptContext context) {
        Objects.requireNonNull(script, "script");
        Objects.requireNonNull(context, "context");
        try {
            runtime.validate(script, context);
        } catch (RuntimeException failure) {
            throw localize(unwrap(failure), context.locale(), script);
        }
    }

    @Override
    public CompletionStage<Object> eval(String script, ScriptContext context) {
        Objects.requireNonNull(script, "script");
        Objects.requireNonNull(context, "context");
        return localizeExecution(runtime.eval(script, context), script, context);
    }

    /**
     * 同步编译并开始执行。编译失败立即抛出 ScriptException；执行失败仍由返回的阶段承载。
     * 与 eval 共用编译缓存，不会为了预检查再次解析或执行脚本。
     */
    public CompletionStage<Object> evalChecked(String script, ScriptContext context) {
        Objects.requireNonNull(script, "script");
        Objects.requireNonNull(context, "context");
        final CompletionStage<Object> execution;
        try {
            execution = runtime.evalChecked(script, context);
        } catch (RuntimeException failure) {
            throw localize(unwrap(failure), context.locale(), script);
        }
        return localizeExecution(execution, script, context);
    }

    private static CompletionStage<Object> localizeExecution(
            CompletionStage<Object> execution, String script, ScriptContext context
    ) {
        CompletableFuture<Object> result = new CompletableFuture<Object>();
        ScriptFutures.cancelWith(result, execution);
        execution.whenComplete((value, failure) -> {
            if (ScriptFutures.cancelIfNeeded(result, failure)) return;
            if (failure == null) {
                result.complete(value);
            } else {
                result.completeExceptionally(localize(
                        unwrap(failure),
                        context.locale(),
                        script));
            }
        });
        return result;
    }

    @Override
    public CompletionStage<Boolean> evalCondition(String script, ScriptContext context) {
        CompletionStage<Object> execution = eval(script, context);
        CompletableFuture<Boolean> result = new CompletableFuture<Boolean>();
        ScriptFutures.cancelWith(result, execution);
        execution.whenComplete((value, failure) -> {
            if (failure != null) {
                if (!ScriptFutures.cancelIfNeeded(result, failure)) result.completeExceptionally(failure);
            } else if (value instanceof Boolean) {
                result.complete((Boolean) value);
            } else if (value instanceof Number || value instanceof CharSequence || value == null) {
                result.complete(Boolean.valueOf(InlineValues.truthy(value)));
            } else {
                result.completeExceptionally(new ScriptException(
                        "invalid-condition",
                        ScriptMessages.text(context.locale(), "invalid-condition", String.valueOf(value)),
                        1, 1, null));
            }
        });
        return result;
    }

    private static ScriptException localize(Throwable failure, Locale locale, String source) {
        if (failure instanceof ScriptException) {
            return (ScriptException) failure;
        }
        if (failure instanceof ContinuationExecutorRequiredException) {
            return new ScriptException(
                    "continuation-executor-required",
                    ScriptMessages.text(locale, "continuation-executor-required"),
                    1,
                    1,
                    failure);
        }
        if (failure instanceof LocalizedException) {
            LocalizedException localized = (LocalizedException) failure;
            int line = sourceLine(localized);
            LocalizedException unknown = localized.stream()
                    .filter(item -> item.getError() == LoadError.UNKNOWN_ACTION)
                    .findFirst()
                    .orElse(null);
            if (unknown != null) {
                Object[] params = unknown.getParams();
                String name = params.length == 0 ? "?" : String.valueOf(params[0]);
                line = lineOf(source, name, line);
                return new ScriptException(
                        "unknown-statement",
                        ScriptMessages.text(
                                locale,
                                "unknown-statement",
                                Integer.valueOf(line),
                                Integer.valueOf(1),
                                name),
                        line,
                        1,
                        failure);
            }
            return actionFailure(failure, locale, line, firstAction(source));
        }
        return actionFailure(failure, locale, 1, firstAction(source));
    }

    private static ScriptException actionFailure(
            Throwable failure,
            Locale locale,
            int line,
            String statement
    ) {
        String detail = failure.getMessage() == null
                ? failure.getClass().getSimpleName()
                : failure.getMessage();
        return new ScriptException(
                "action-failed",
                ScriptMessages.text(
                        locale,
                        "action-failed",
                        Integer.valueOf(line),
                        Integer.valueOf(1),
                        statement,
                        detail),
                line,
                1,
                failure);
    }

    private static int sourceLine(LocalizedException failure) {
        LocalizedException block = failure.stream()
                .filter(item -> item.getError() == LoadError.BLOCK_ERROR)
                .findFirst()
                .orElse(null);
        if (block == null) {
            return 1;
        }
        Object[] params = block.getParams();
        if (params.length > 1 && params[1] instanceof Number) {
            return Math.max(1, ((Number) params[1]).intValue() - 1);
        }
        return 1;
    }

    private static int lineOf(String source, String token, int fallback) {
        String[] lines = source.split("\\r?\\n", -1);
        for (int index = 0; index < lines.length; index++) {
            String trimmed = lines[index].trim();
            if (!trimmed.startsWith("#") && !trimmed.startsWith("//")
                    && containsToken(trimmed, token)) {
                return index + 1;
            }
        }
        return fallback;
    }

    private static boolean containsToken(String source, String token) {
        int index = source.indexOf(token);
        if (index < 0) {
            return false;
        }
        boolean left = index == 0 || !Character.isJavaIdentifierPart(source.charAt(index - 1));
        int end = index + token.length();
        boolean right = end == source.length() || !Character.isJavaIdentifierPart(source.charAt(end));
        return left && right;
    }

    private static String firstAction(String source) {
        String[] lines = source.split("[;\\r\\n]+", -1);
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("//")
                    || trimmed.startsWith("def ") || "}".equals(trimmed)) {
                continue;
            }
            int end = 0;
            while (end < trimmed.length()
                    && !Character.isWhitespace(trimmed.charAt(end))) {
                end++;
            }
            return trimmed.substring(0, end);
        }
        return "kether";
    }

    private static Throwable unwrap(Throwable failure) {
        if (failure instanceof CompletionException && failure.getCause() != null) {
            return failure.getCause();
        }
        return failure;
    }

    int cachedScriptCount() {
        return runtime.cachedScriptCount();
    }

    long compilationCount() {
        return runtime.compilationCount();
    }

    private static final class ContinuationExecutorRequiredException
            extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        private ContinuationExecutorRequiredException() {
            super("Asynchronous script actions require an explicit continuation Executor");
        }
    }
}
