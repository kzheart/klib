/* Kether action semantics: Copyright (c) 2018 Bkm016, MIT. See THIRD_PARTY_NOTICES.md. */
package me.kzheart.klib.script;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import me.kzheart.klib.script.kether.core.ParsedAction;
import me.kzheart.klib.script.kether.core.QuestAction;
import me.kzheart.klib.script.kether.core.QuestActionParser;
import me.kzheart.klib.script.kether.core.QuestContext;
import me.kzheart.klib.script.kether.core.QuestReader;
import org.apache.commons.jexl3.JexlBuilder;
import org.apache.commons.jexl3.JexlEngine;
import org.apache.commons.jexl3.JexlExpression;
import org.apache.commons.jexl3.JexlScript;
import org.apache.commons.jexl3.MapContext;

/** 消费动作树的内置语句；参数解析与独立语句的严格解析分开。 */
final class StructuredScriptActions {
    private static final JexlEngine JEXL = new JexlBuilder().cache(256).create();
    private static final Pattern INLINE = Pattern.compile("\\{\\{(.*?)}}", Pattern.DOTALL);

    private StructuredScriptActions() { }

    static void install(StatementRegistry registry) {
        QuestActionParser tell = unary((frame, value) -> {
            ScriptContext context = CoreScriptRuntime.context(frame);
            String message = InlineValues.interpolate(text(value), context);
            Object sender = context.sender().orElse(null);
            // 原框架把 @sender 替换为发送者名称。
            if (message.contains("@sender")) {
                message = message.replace("@sender", sender == null ? "null" : String.valueOf(context.requireService(ScriptSenderQuery.class).name(sender)));
            }
            context.requireService(MessageSink.class).send(sender, message);
            return completed(message);
        });
        registry.registerBuiltinKether("tell", tell);
        registry.registerBuiltinKether("send", tell);
        registry.registerBuiltinKether("message", tell);
        QuestActionParser colored = unary((frame, value) -> completed(color(text(value))));
        registry.registerBuiltinKether("colored", colored);
        registry.registerBuiltinKether("color", colored);
        QuestActionParser inline = unary((frame, value) -> interpolate(frame, text(value)));
        registry.registerBuiltinKether("inline", inline);
        registry.registerBuiltinKether("function", inline);
        QuestActionParser calc = calculation(false);
        registry.registerBuiltinKether("calc", calc);
        registry.registerBuiltinKether("calculate", calc);
        registry.registerBuiltinKether("invoke", calculation(true));
        registry.registerBuiltinKether("case", QuestActionParser.of(StructuredScriptActions::caseAction));
    }

    private static QuestActionParser unary(ValueAction action) {
        return QuestActionParser.of(reader -> {
            ParsedAction<?> input = reader.nextValue();
            return action(frame -> follow(frame, frame.newFrame(input).run(), value -> action.run(frame, value)));
        });
    }

    private static QuestActionParser calculation(boolean script) {
        return QuestActionParser.of(reader -> {
            if (consume(reader, "dynamic")) {
                ParsedAction<?> expression = reader.nextValue();
                return action(frame -> follow(frame, frame.newFrame(expression).run(), value ->
                        completed(evaluate(text(value), script, frame))));
            }
            String source = reader.nextToken();
            if (script) {
                JexlScript compiled = JEXL.createScript(source);
                return action(frame -> completed(compiled.execute(new MapContext(variables(frame)))));
            }
            JexlExpression compiled = JEXL.createExpression(source);
            return action(frame -> completed(compiled.evaluate(new MapContext(variables(frame)))));
        });
    }

    private static Object evaluate(String source, boolean script, QuestContext.Frame frame) {
        MapContext context = new MapContext(variables(frame));
        return script ? JEXL.createScript(source).execute(context) : JEXL.createExpression(source).evaluate(context);
    }

    private static Map<String, Object> variables(QuestContext.Frame frame) {
        Map<String, Object> result = new LinkedHashMap<String, Object>(CoreScriptRuntime.context(frame).variables());
        List<QuestContext.VarTable> tables = new ArrayList<QuestContext.VarTable>();
        for (QuestContext.VarTable table = frame.variables(); table != null; table = table.parent()) {
            tables.add(table);
        }
        Collections.reverse(tables);
        for (QuestContext.VarTable table : tables) {
            for (Map.Entry<String, Object> entry : table.values()) {
                if (!entry.getKey().startsWith("~klib:")) result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    private static QuestAction<Object> caseAction(QuestReader reader) {
        return caseAction(reader, false, Collections.<String>emptyList());
    }

    static QuestAction<Object> caseAction(QuestReader reader, boolean legacy, List<String> names) {
        ParsedAction<?> input = reader.nextValue();
        reader.expect("[");
        List<Branch> branches = new ArrayList<Branch>();
        while (!consume(reader, "]")) {
            String token = reader.nextToken();
            if ("else".equals(token)) {
                if (legacy) consume(reader, "->");
                branches.add(new Branch(null, Collections.emptyList(), caseValue(reader, legacy, names)));
            } else if ("when".equals(token)) {
                int index = reader.getIndex();
                Operator operator = Operator.parse(reader.nextToken());
                if (operator == null) {
                    reader.setIndex(index);
                    operator = Operator.EQUAL;
                }
                List<ParsedAction<?>> conditions = new ArrayList<ParsedAction<?>>();
                if (consume(reader, "[")) {
                    while (!consume(reader, "]")) conditions.add(reader.nextValue());
                } else {
                    conditions.add(reader.nextValue());
                }
                String arrow = reader.nextToken();
                if (!"->".equals(arrow) && !"then".equals(arrow)) {
                    throw new IllegalArgumentException("Expected then or ->, got " + arrow);
                }
                branches.add(new Branch(operator, conditions, caseValue(reader, legacy, names)));
            } else {
                throw new IllegalArgumentException("Expected when or else, got " + token);
            }
        }
        return action(frame -> follow(frame, frame.newFrame(input).run(), value -> {
            CompletableFuture<Branch> selected = CompletableFuture.completedFuture(null);
            for (Branch branch : branches) {
                selected = follow(frame, selected, previous -> {
                    if (previous != null) return CompletableFuture.completedFuture(previous);
                    return follow(frame, branch.matches(frame, value), matches ->
                            CompletableFuture.completedFuture(matches ? branch : null));
                });
            }
            return follow(frame, selected, branch -> branch == null
                    ? completed(null) : frame.newFrame(branch.action).run());
        }));
    }

    private static ParsedAction<?> caseValue(QuestReader reader, boolean legacy, List<String> names) {
        int start = reader.getIndex();
        ParsedAction<?> parsed = reader.nextValue();
        if (!legacy) return parsed;
        String first = reader.source(start, reader.getIndex()).trim();
        if (!caseLabel(first, names)) return parsed;
        final StringBuilder label = new StringBuilder(first);
        boolean joined = false;
        while (reader.hasNext() && !reader.hasLineBreakBeforeNextToken()) {
            int index = reader.getIndex();
            String next = reader.nextToken();
            if (!caseLabel(next, names)) { reader.setIndex(index); break; }
            label.append(' ').append(next);
            joined = true;
        }
        return joined ? new ParsedAction<Object>(action(frame -> completed(label.toString()))) : parsed;
    }

    private static boolean caseLabel(String value, List<String> names) {
        if (!value.matches("[A-Z][A-Z0-9_-]*")) return false;
        for (String name : names) {
            if (name.substring(name.lastIndexOf(':') + 1).equalsIgnoreCase(value)) return false;
        }
        return true;
    }

    private static CompletableFuture<Object> interpolate(QuestContext.Frame frame, String source) {
        Matcher matcher = INLINE.matcher(source);
        CompletableFuture<StringBuilder> output = CompletableFuture.completedFuture(new StringBuilder());
        int end = 0;
        while (matcher.find()) {
            final String prefix = source.substring(end, matcher.start());
            final String expression = matcher.group(1).trim();
            output = follow(frame, output, buffer -> {
                buffer.append(prefix);
                // 保留 Klib 的 {{ name }} 变量简写；其他内容按脚本执行。
                CompletableFuture<Object> value = expression.matches("[A-Za-z0-9_.-]+")
                        ? completed(CoreScriptRuntime.context(frame).variableOrNull(expression))
                        : CoreScriptRuntime.evalNested(frame, expression).toCompletableFuture();
                return follow(frame, value, result -> CompletableFuture.completedFuture(buffer.append(text(result))));
            });
            end = matcher.end();
        }
        final String tail = source.substring(end);
        return follow(frame, output, result -> completed(
                InlineValues.interpolate(result.append(tail).toString(), CoreScriptRuntime.context(frame))));
    }

    static boolean consume(QuestReader reader, String token) {
        int index = reader.getIndex();
        if (reader.hasNext() && token.equals(reader.nextToken())) return true;
        reader.setIndex(index);
        return false;
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }

    static String color(String value) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '&' && index + 1 < value.length()) {
                char code = Character.toLowerCase(value.charAt(index + 1));
                if (code == '#' && index + 7 < value.length()
                        && value.substring(index + 2, index + 8).matches("[0-9a-fA-F]{6}")) {
                    result.append('\u00a7').append('x');
                    for (int digit = index + 2; digit < index + 8; digit++) {
                        result.append('\u00a7').append(Character.toLowerCase(value.charAt(digit)));
                    }
                    index += 7;
                    continue;
                }
                if ("0123456789abcdefklmnorx".indexOf(code) >= 0) {
                    result.append('\u00a7').append(code);
                    index++;
                    continue;
                }
            }
            result.append(current);
        }
        return result.toString();
    }

    /** 仅异步恢复需要调度；同步动作不要求宿主提供 executor。 */
    private static <T, R> CompletableFuture<R> follow(QuestContext.Frame frame, CompletableFuture<T> input,
                                                     Function<T, CompletableFuture<R>> next) {
        return input.isDone() ? input.thenCompose(next) : input.thenComposeAsync(next, frame.context().getExecutor());
    }

    private static QuestAction<Object> action(Function<QuestContext.Frame, CompletableFuture<Object>> operation) {
        return new QuestAction<Object>() {
            @Override public CompletableFuture<Object> process(QuestContext.Frame frame) { return operation.apply(frame); }
        };
    }

    private static CompletableFuture<Object> completed(Object value) { return CompletableFuture.completedFuture(value); }

    private interface ValueAction {
        CompletableFuture<Object> run(QuestContext.Frame frame, Object value);
    }

    private static final class Branch {
        private final Operator operator;
        private final List<ParsedAction<?>> conditions;
        private final ParsedAction<?> action;

        private Branch(Operator operator, List<ParsedAction<?>> conditions, ParsedAction<?> action) {
            this.operator = operator;
            this.conditions = conditions;
            this.action = action;
        }

        private CompletableFuture<Boolean> matches(QuestContext.Frame frame, Object input) {
            if (operator == null) return CompletableFuture.completedFuture(true);
            List<CompletableFuture<Object>> values = new ArrayList<CompletableFuture<Object>>();
            for (ParsedAction<?> condition : conditions) values.add(frame.newFrame(condition).run());
            return follow(frame, CompletableFuture.allOf(values.toArray(new CompletableFuture<?>[0])), ignored -> {
                List<Object> results = new ArrayList<Object>();
                for (CompletableFuture<Object> value : values) results.add(value.join());
                if (operator == Operator.IN || operator == Operator.CONTAINS) {
                    Object right = results.size() == 1 ? results.get(0) : results;
                    return CompletableFuture.completedFuture(operator.matches(input, right));
                }
                for (Object value : results) {
                    if (operator.matches(input, value)) return CompletableFuture.completedFuture(true);
                }
                return CompletableFuture.completedFuture(false);
            });
        }
    }

    private enum Operator {
        EQUAL, NOT_EQUAL, STRICT, IDENTITY, IGNORE_CASE, GT, GTE, LT, LTE, IN, CONTAINS;

        static Operator parse(String value) {
            switch (value.toLowerCase(Locale.ROOT)) {
                case "==": case "is": return EQUAL;
                case "!=": case "!is": case "not": return NOT_EQUAL;
                case "=!": case "is!": return STRICT;
                case "=!!": case "is!!": return IDENTITY;
                case "=?": case "is?": return IGNORE_CASE;
                case ">": case "gt": return GT;
                case ">=": case "gte": return GTE;
                case "<": case "lt": return LT;
                case "<=": case "lte": return LTE;
                case "in": return IN;
                case "contains": case "has": return CONTAINS;
                default: return null;
            }
        }

        boolean matches(Object left, Object right) {
            switch (this) {
                case EQUAL: return inferredEquals(left, right);
                case NOT_EQUAL: return !inferredEquals(left, right);
                case STRICT: return Objects.equals(left, right);
                case IDENTITY: return left == right;
                case IGNORE_CASE: return text(left).equalsIgnoreCase(text(right));
                case GT: return number(left).compareTo(number(right)) > 0;
                case GTE: return number(left).compareTo(number(right)) >= 0;
                case LT: return number(left).compareTo(number(right)) < 0;
                case LTE: return number(left).compareTo(number(right)) <= 0;
                case IN: return contains(right, left);
                case CONTAINS: return contains(left, right);
                default: throw new AssertionError(this);
            }
        }

        private static boolean inferredEquals(Object left, Object right) {
            Object first = infer(left);
            Object second = infer(right);
            if (first instanceof Number && second instanceof Number) {
                return ((Number) first).doubleValue() == ((Number) second).doubleValue();
            }
            return Objects.equals(first, second);
        }

        private static Object infer(Object value) {
            if (!(value instanceof String)) return value;
            String string = (String) value;
            try { return Double.valueOf(string); }
            catch (NumberFormatException ignored) { }
            if ("true".equals(string)) return Boolean.TRUE;
            if ("false".equals(string)) return Boolean.FALSE;
            return value;
        }

        private static BigDecimal number(Object value) { return new BigDecimal(text(value)); }

        private static boolean contains(Object collection, Object value) {
            if (collection instanceof Collection<?>) return ((Collection<?>) collection).contains(value);
            if (collection instanceof Map<?, ?>) return ((Map<?, ?>) collection).containsKey(value);
            if (collection != null && collection.getClass().isArray()) {
                for (int index = 0; index < Array.getLength(collection); index++) {
                    if (Objects.equals(Array.get(collection, index), value)) return true;
                }
                return false;
            }
            return collection != null && text(collection).contains(text(value));
        }
    }
}
