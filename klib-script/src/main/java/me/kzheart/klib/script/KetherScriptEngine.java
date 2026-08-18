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
        Objects.requireNonNull(registry, "registry");
        this.runtime = new CoreScriptRuntime(
                registry,
                unknownResolver,
                Objects.requireNonNull(continuationExecutor, "continuationExecutor"));
        BuiltInStatements.install(registry);
    }

    @Override
    public CompletionStage<Object> eval(String script, ScriptContext context) {
        Objects.requireNonNull(script, "script");
        Objects.requireNonNull(context, "context");
        CompletionStage<Object> execution = runtime.eval(script, context);
        CompletableFuture<Object> result = new CompletableFuture<Object>();
        execution.whenComplete((value, failure) -> {
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
        return eval(script, context).thenApply(value -> {
            if (value instanceof Boolean) {
                return (Boolean) value;
            }
            if (value instanceof Number || value instanceof CharSequence || value == null) {
                return Boolean.valueOf(InlineValues.truthy(value));
            }
            throw new ScriptException(
                    "invalid-condition",
                    ScriptMessages.text(context.locale(), "invalid-condition", String.valueOf(value)),
                    1,
                    1,
                    null);
        });
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
