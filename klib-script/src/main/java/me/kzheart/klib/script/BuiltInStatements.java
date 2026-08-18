package me.kzheart.klib.script;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

final class BuiltInStatements {

    private BuiltInStatements() {
    }

    static void install(StatementRegistry registry) {
        if (!registry.beginBuiltinInstall()) {
            return;
        }
        literal(registry, "literal");
        literal(registry, "inline");
        registry.registerBuiltin("klib", "set", BuiltInStatements::set);
        registry.registerBuiltin("klib", "get", BuiltInStatements::get);
        registry.registerBuiltin("klib", "unset", BuiltInStatements::unset);
        registry.registerBuiltin("klib", "eq", compare(Comparison.EQUAL));
        registry.registerBuiltin("klib", "ne", compare(Comparison.NOT_EQUAL));
        registry.registerBuiltin("klib", "gt", compare(Comparison.GREATER));
        registry.registerBuiltin("klib", "gte", compare(Comparison.GREATER_OR_EQUAL));
        registry.registerBuiltin("klib", "lt", compare(Comparison.LESS));
        registry.registerBuiltin("klib", "lte", compare(Comparison.LESS_OR_EQUAL));
        registry.registerBuiltin("klib", "not", BuiltInStatements::not);
        registry.registerBuiltin("klib", "and", logical(true));
        registry.registerBuiltin("klib", "or", logical(false));
        registry.registerBuiltin("klib", "add", arithmetic(Arithmetic.ADD));
        registry.registerBuiltin("klib", "sub", arithmetic(Arithmetic.SUBTRACT));
        registry.registerBuiltin("klib", "mul", arithmetic(Arithmetic.MULTIPLY));
        registry.registerBuiltin("klib", "div", arithmetic(Arithmetic.DIVIDE));
        registry.registerBuiltin("klib", "if", BuiltInStatements::conditional);
        registry.registerBuiltin("klib", "namespace", BuiltInStatements::namespace);
        registry.registerBuiltin("klib", "tell", BuiltInStatements::tell);
        registry.registerBuiltin("klib", "command", BuiltInStatements::command);
        registry.registerBuiltin("klib", "papi", BuiltInStatements::papi);
        registry.registerBuiltin("klib", "check", BuiltInStatements::check);
        registry.registerBuiltin("klib", "perm", BuiltInStatements::permission);
        registry.registerBuiltin("klib", "all", BuiltInStatements::all);
        registry.registerBuiltin("klib", "any", BuiltInStatements::any);
        registry.registerBuiltin("klib", "type", BuiltInStatements::type);
        registry.registerBuiltin("klib", "delay", BuiltInStatements::delay);
        registry.registerBuiltin("klib", "list", BuiltInStatements::list);
        registry.registerBuiltin("klib", "join", BuiltInStatements::join);
    }

    private static void literal(StatementRegistry registry, String name) {
        registry.registerBuiltin("klib", name, (call, context) -> completed(call.text(0, context)));
    }

    private static CompletionStage<Object> set(StatementCall call, ScriptContext context) {
        require(call, 2, context, "value");
        int valueIndex = "to".equalsIgnoreCase(call.argument(1)) ? 2 : 1;
        require(call, valueIndex + 1, context, "value");
        Object value = call.arguments().size() == valueIndex + 1
                ? call.value(valueIndex, context)
                : call.text(valueIndex, context);
        context.setVariable(call.argument(0), value);
        return completed(value);
    }

    private static CompletionStage<Object> get(StatementCall call, ScriptContext context) {
        require(call, 1, context, "name");
        return completed(context.variableOrNull(call.argument(0)));
    }

    private static CompletionStage<Object> unset(StatementCall call, ScriptContext context) {
        require(call, 1, context, "name");
        return completed(context.removeVariable(call.argument(0)));
    }

    private static QuestActionParser compare(Comparison comparison) {
        return (call, context) -> {
            require(call, 2, context, "right");
            Object left = call.value(0, context);
            Object right = call.value(1, context);
            int result = compareValues(left, right);
            return completed(Boolean.valueOf(comparison.matches(result)));
        };
    }

    private static CompletionStage<Object> not(StatementCall call, ScriptContext context) {
        require(call, 1, context, "value");
        return completed(Boolean.valueOf(!InlineValues.truthy(call.value(0, context))));
    }

    private static QuestActionParser logical(boolean and) {
        return (call, context) -> {
            require(call, 1, context, "value");
            boolean result = and;
            for (int index = 0; index < call.arguments().size(); index++) {
                boolean value = InlineValues.truthy(call.value(index, context));
                result = and ? result && value : result || value;
            }
            return completed(Boolean.valueOf(result));
        };
    }

    private static QuestActionParser arithmetic(Arithmetic arithmetic) {
        return (call, context) -> {
            require(call, 2, context, "right");
            BigDecimal result = number(call.value(0, context), context);
            for (int index = 1; index < call.arguments().size(); index++) {
                result = arithmetic.apply(result, number(call.value(index, context), context));
            }
            return completed(result.stripTrailingZeros());
        };
    }

    private static CompletionStage<Object> conditional(StatementCall call, ScriptContext context) {
        require(call, 3, context, "then-block");
        String condition = call.argument(0);
        int thenIndex = "then".equalsIgnoreCase(call.argument(1)) ? 2 : 1;
        String thenBlock = call.argument(thenIndex);
        int elseIndex = thenIndex + 1;
        return call.eval(condition, context).thenCompose(value -> {
            if (InlineValues.truthy(value)) {
                return call.eval(thenBlock, context);
            }
            if (elseIndex + 1 < call.arguments().size()
                    && "else".equalsIgnoreCase(call.argument(elseIndex))) {
                return call.eval(call.argument(elseIndex + 1), context);
            }
            return completed(null);
        });
    }

    private static CompletionStage<Object> namespace(StatementCall call, ScriptContext context) {
        require(call, 2, context, "block");
        return call.eval(call.argument(1), context.withNamespaces(call.argument(0)));
    }

    private static CompletionStage<Object> tell(StatementCall call, ScriptContext context) {
        require(call, 1, context, "message");
        String message = call.text(0, context);
        MessageSink sink = context.requireService(MessageSink.class);
        sink.send(context.sender().orElse(null), message);
        return completed(message);
    }

    private static CompletionStage<Object> command(StatementCall call, ScriptContext context) {
        require(call, 1, context, "command");
        int commandIndex = "inline".equalsIgnoreCase(call.argument(0)) ? 1 : 0;
        require(call, commandIndex + 1, context, "command");
        int asIndex = indexOf(call.arguments(), "as", commandIndex + 1);
        int commandEnd = asIndex < 0 ? call.arguments().size() : asIndex;
        String command = text(call, commandIndex, commandEnd, context);
        Object sender = context.sender().orElse(null);
        if (asIndex >= 0 && asIndex + 1 < call.arguments().size()
                && "console".equalsIgnoreCase(call.argument(asIndex + 1))) {
            sender = null;
        }
        return completed(context.requireService(CommandSink.class).dispatch(
                sender,
                command));
    }

    private static CompletionStage<Object> papi(StatementCall call, ScriptContext context) {
        require(call, 1, context, "text");
        return completed(context.requireService(PlaceholderResolver.class).resolve(
                context.sender().orElse(null),
                call.text(0, context)));
    }

    private static CompletionStage<Object> check(StatementCall call, ScriptContext context) {
        return completed(Boolean.valueOf(evaluateCheck(call.arguments(), context)));
    }

    private static CompletionStage<Object> permission(StatementCall call, ScriptContext context) {
        require(call, 1, context, "permission");
        PlayerQuery query = context.requireService(PlayerQuery.class);
        return completed(Boolean.valueOf(query.hasPermission(
                context.sender().orElse(null),
                InlineValues.text(call.argument(0), context))));
    }

    private static CompletionStage<Object> all(StatementCall call, ScriptContext context) {
        return completed(Boolean.valueOf(evaluateGroup(call.arguments(), context, true)));
    }

    private static CompletionStage<Object> any(StatementCall call, ScriptContext context) {
        return completed(Boolean.valueOf(evaluateGroup(call.arguments(), context, false)));
    }

    private static CompletionStage<Object> type(StatementCall call, ScriptContext context) {
        require(call, 2, context, "value");
        String type = call.argument(0).toLowerCase(java.util.Locale.ROOT);
        String value = InlineValues.text(call.argument(1), context);
        if ("boolean".equals(type) || "bool".equals(type)) {
            if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
                throw new IllegalArgumentException("Invalid boolean: " + value);
            }
            return completed(Boolean.valueOf(value));
        }
        if ("int".equals(type) || "integer".equals(type)) {
            return completed(Integer.valueOf(value));
        }
        if ("long".equals(type)) {
            return completed(Long.valueOf(value));
        }
        if ("double".equals(type)) {
            return completed(Double.valueOf(value));
        }
        if ("number".equals(type) || "decimal".equals(type)) {
            return completed(new BigDecimal(value));
        }
        if ("string".equals(type)) {
            return completed(value);
        }
        throw new IllegalArgumentException("Unknown type: " + type);
    }

    private static CompletionStage<Object> delay(StatementCall call, ScriptContext context) {
        require(call, 1, context, "duration");
        Duration duration = parseDuration(call.argument(0), context);
        return context.requireService(DelayScheduler.class).delay(duration);
    }

    private static CompletionStage<Object> list(StatementCall call, ScriptContext context) {
        List<Object> values = new ArrayList<Object>();
        for (int index = 0; index < call.arguments().size(); index++) {
            values.add(call.value(index, context));
        }
        return completed(Collections.unmodifiableList(values));
    }

    private static CompletionStage<Object> join(StatementCall call, ScriptContext context) {
        require(call, 2, context, "value");
        String delimiter = InlineValues.text(call.argument(0), context);
        StringBuilder result = new StringBuilder();
        for (int index = 1; index < call.arguments().size(); index++) {
            if (index > 1) {
                result.append(delimiter);
            }
            result.append(InlineValues.text(call.argument(index), context));
        }
        return completed(result.toString());
    }

    private static void require(
            StatementCall call,
            int count,
            ScriptContext context,
            String argument
    ) {
        if (call.arguments().size() < count) {
            throw new IllegalArgumentException(
                    ScriptMessages.text(context.locale(), "missing-argument", argument));
        }
    }

    private static int compareValues(Object left, Object right) {
        if (left == null || right == null) {
            return Objects.equals(left, right) ? 0 : left == null ? -1 : 1;
        }
        if (left instanceof Number && right instanceof Number) {
            return new BigDecimal(String.valueOf(left)).compareTo(new BigDecimal(String.valueOf(right)));
        }
        return String.valueOf(left).compareTo(String.valueOf(right));
    }

    private static boolean evaluateCheck(List<String> arguments, ScriptContext context) {
        if (arguments.size() < 4) {
            throw new IllegalArgumentException("check requires a source, operator and expected value");
        }
        Object left;
        int operatorIndex;
        if ("papi".equalsIgnoreCase(arguments.get(0))) {
            left = context.requireService(PlaceholderResolver.class).resolve(
                    context.sender().orElse(null),
                    InlineValues.text(arguments.get(1), context));
            operatorIndex = 2;
        } else if ("player".equalsIgnoreCase(arguments.get(0))) {
            left = context.requireService(PlayerQuery.class).property(
                    context.sender().orElse(null),
                    arguments.get(1));
            operatorIndex = 2;
        } else {
            left = InlineValues.value(arguments.get(0), context);
            operatorIndex = 1;
        }
        if (operatorIndex + 1 >= arguments.size()) {
            throw new IllegalArgumentException("check is missing its expected value");
        }
        String operator = arguments.get(operatorIndex);
        Object right = InlineValues.value(arguments.get(operatorIndex + 1), context);
        int compared = compareValues(coerceComparable(left), coerceComparable(right));
        if ("==".equals(operator) || "=".equals(operator)) {
            return compared == 0;
        }
        if ("!=".equals(operator)) {
            return compared != 0;
        }
        if (">".equals(operator)) {
            return compared > 0;
        }
        if (">=".equals(operator)) {
            return compared >= 0;
        }
        if ("<".equals(operator)) {
            return compared < 0;
        }
        if ("<=".equals(operator)) {
            return compared <= 0;
        }
        throw new IllegalArgumentException("Unknown check operator: " + operator);
    }

    private static boolean evaluateGroup(
            List<String> rawArguments,
            ScriptContext context,
            boolean requireAll
    ) {
        List<String> arguments = new ArrayList<String>(rawArguments);
        if (!arguments.isEmpty() && "[".equals(arguments.get(0))) {
            arguments.remove(0);
        }
        if (!arguments.isEmpty() && "]".equals(arguments.get(arguments.size() - 1))) {
            arguments.remove(arguments.size() - 1);
        }
        if (arguments.isEmpty()) {
            return requireAll;
        }
        int index = 0;
        boolean result = requireAll;
        while (index < arguments.size()) {
            boolean value;
            String action = arguments.get(index);
            if ("check".equalsIgnoreCase(action)) {
                int length = conditionLength(arguments, index + 1);
                value = evaluateCheck(arguments.subList(index + 1, index + 1 + length), context);
                index += length + 1;
            } else if ("perm".equalsIgnoreCase(action)) {
                if (index + 1 >= arguments.size()) {
                    throw new IllegalArgumentException("perm is missing its permission");
                }
                value = context.requireService(PlayerQuery.class).hasPermission(
                        context.sender().orElse(null),
                        InlineValues.text(arguments.get(index + 1), context));
                index += 2;
            } else {
                value = InlineValues.truthy(InlineValues.value(action, context));
                index++;
            }
            result = requireAll ? result && value : result || value;
        }
        return result;
    }

    private static int conditionLength(List<String> arguments, int start) {
        if (start >= arguments.size()) {
            throw new IllegalArgumentException("check is missing its source");
        }
        String source = arguments.get(start);
        if ("papi".equalsIgnoreCase(source) || "player".equalsIgnoreCase(source)) {
            if (start + 3 >= arguments.size()) {
                throw new IllegalArgumentException("check expression is incomplete");
            }
            return 4;
        }
        if (start + 2 >= arguments.size()) {
            throw new IllegalArgumentException("check expression is incomplete");
        }
        return 3;
    }

    private static Object coerceComparable(Object value) {
        if (value instanceof Number || value == null) {
            return value;
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return value;
        }
    }

    private static int indexOf(List<String> values, String expected, int start) {
        for (int index = start; index < values.size(); index++) {
            if (expected.equalsIgnoreCase(values.get(index))) {
                return index;
            }
        }
        return -1;
    }

    private static String text(
            StatementCall call,
            int start,
            int end,
            ScriptContext context
    ) {
        StringBuilder result = new StringBuilder();
        for (int index = start; index < end; index++) {
            if (result.length() > 0) {
                result.append(' ');
            }
            result.append(InlineValues.text(call.argument(index), context));
        }
        return result.toString();
    }

    private static BigDecimal number(Object value, ScriptContext context) {
        try {
            return value instanceof BigDecimal
                    ? (BigDecimal) value
                    : new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(
                    ScriptMessages.text(context.locale(), "invalid-number", String.valueOf(value)),
                    failure);
        }
    }

    private static Duration parseDuration(String value, ScriptContext context) {
        try {
            String normalized = value.toLowerCase(java.util.Locale.ROOT);
            if (normalized.endsWith("ms")) {
                return Duration.ofMillis(Long.parseLong(normalized.substring(0, normalized.length() - 2)));
            }
            if (normalized.endsWith("s")) {
                return Duration.ofMillis(new BigDecimal(
                        normalized.substring(0, normalized.length() - 1))
                        .multiply(BigDecimal.valueOf(1000L)).longValueExact());
            }
            if (normalized.endsWith("t")) {
                return Duration.ofMillis(Long.parseLong(
                        normalized.substring(0, normalized.length() - 1)) * 50L);
            }
            return Duration.ofMillis(Long.parseLong(normalized));
        } catch (ArithmeticException | NumberFormatException failure) {
            throw new IllegalArgumentException(
                    ScriptMessages.text(context.locale(), "invalid-number", value),
                    failure);
        }
    }

    private static CompletionStage<Object> completed(Object value) {
        return CompletableFuture.completedFuture(value);
    }

    private enum Comparison {
        EQUAL {
            @Override
            boolean matches(int value) {
                return value == 0;
            }
        },
        NOT_EQUAL {
            @Override
            boolean matches(int value) {
                return value != 0;
            }
        },
        GREATER {
            @Override
            boolean matches(int value) {
                return value > 0;
            }
        },
        GREATER_OR_EQUAL {
            @Override
            boolean matches(int value) {
                return value >= 0;
            }
        },
        LESS {
            @Override
            boolean matches(int value) {
                return value < 0;
            }
        },
        LESS_OR_EQUAL {
            @Override
            boolean matches(int value) {
                return value <= 0;
            }
        };

        abstract boolean matches(int value);
    }

    private enum Arithmetic {
        ADD {
            @Override
            BigDecimal apply(BigDecimal left, BigDecimal right) {
                return left.add(right);
            }
        },
        SUBTRACT {
            @Override
            BigDecimal apply(BigDecimal left, BigDecimal right) {
                return left.subtract(right);
            }
        },
        MULTIPLY {
            @Override
            BigDecimal apply(BigDecimal left, BigDecimal right) {
                return left.multiply(right);
            }
        },
        DIVIDE {
            @Override
            BigDecimal apply(BigDecimal left, BigDecimal right) {
                return left.divide(right, MathContext.DECIMAL128);
            }
        };

        abstract BigDecimal apply(BigDecimal left, BigDecimal right);
    }
}
