/* Kether: Copyright (c) 2018 Bkm016. Coerce: Copyright SpongePowered and contributors.
 * MIT License. See THIRD_PARTY_NOTICES.md. */
package me.kzheart.klib.script;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
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

/** 原 CheckType 双操作数动作及其只读宿主查询边界。 */
final class NativeCheckActions {
    private static final Pattern BRACKETS = Pattern.compile("^([(\\[{]?)(.+?)([)\\]}]?)$");
    // Guava Doubles.tryParse 使用的浮点格式边界；不接受 NaN/Infinity 后附类型后缀。
    private static final Pattern FLOAT = Pattern.compile("[+-]?(?:NaN|Infinity|(?:(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?[fFdD]?|0[xX](?:[0-9a-fA-F]+(?:\\.[0-9a-fA-F]*)?|\\.[0-9a-fA-F]+)[pP][+-]?[0-9]+[fFdD]?))");

    private NativeCheckActions() { }

    static void install(StatementRegistry registry) {
        registry.registerBuiltinKether("true", constant("true"));
        registry.registerBuiltinKether("false", constant("false"));
        registry.registerBuiltinKether("null", constant(null));
        registry.registerBuiltinKether("check", QuestActionParser.of(reader -> {
            ParsedAction<?> left = requiredValue(reader);
            String symbol = requiredToken(reader);
            ParsedAction<?> right = requiredValue(reader);
            // 原 combinationParser 按左、symbol、右顺序执行，再解析 CheckType。
            return action(frame -> follow(frame, completed(null), ignored ->
                    follow(frame, frame.newFrame(left).run(), first ->
                    follow(frame, frame.newFrame(right).run(), second ->
                            completed(Boolean.valueOf(Operator.of(symbol).check(first, second)))))));
        }));
        registry.registerBuiltinKether("player", QuestActionParser.of(reader -> {
            String property = playerOperator(reader);
            PlayerQuery.Method method = null;
            int mark = reader.getIndex();
            if (reader.hasNext() && reader.peek() != '}' && reader.peek() != ']') {
                // '=' 留给外围 check 的已公开 Klib alias，不作为写入。
                switch (reader.nextToken()) {
                    case "to": method = PlayerQuery.Method.MODIFY; break;
                    case "add": case "increase": case "+": method = PlayerQuery.Method.INCREASE; break;
                    case "sub": case "decrease": case "-": method = PlayerQuery.Method.DECREASE; break;
                    default: reader.setIndex(mark);
                }
            }
            if (method != null) {
                final PlayerQuery.Method write = method;
                ParsedAction<?> value = requiredValue(reader);
                return action(frame -> follow(frame, frame.newFrame(value).run(), result -> {
                    ScriptContext context = CoreScriptRuntime.context(frame);
                    if (!context.requireService(PlayerQuery.class).write(player(context), property, write, result)) {
                        throw new IllegalArgumentException("Player \"" + property + "\" is not writable.");
                    }
                    return completed(null);
                }));
            }
            return action(frame -> {
                ScriptContext context = CoreScriptRuntime.context(frame);
                Object selected = player(context);
                Object value = context.requireService(PlayerQuery.class).property(selected, property);
                if (value == null) throw new IllegalArgumentException("Player \"" + property + "\" is not readable.");
                return completed(value);
            });
        }));
        QuestActionParser placeholder = QuestActionParser.of(reader -> {
            ParsedAction<?> input = requiredValue(reader);
            return action(frame -> {
                CompletableFuture<Object> result = follow(frame, frame.newFrame(input).run(), value ->
                        completed(placeholder(frame, value == null ? "" : trimIndent(value.toString()))));
                // 原 .str 捕获 input/callback 的首次异常，再对空字符串执行 callback。
                return follow(frame, result.handle(Outcome::new), outcome -> {
                    if (outcome.failure == null) return completed(outcome.value);
                    outcome.failure.printStackTrace();
                    return completed(placeholder(frame, ""));
                });
            });
        });
        registry.registerBuiltinKether("papi", placeholder);
        registry.registerBuiltinKether("placeholder", placeholder);
    }

    /** 原框架玩家操作名（小写、空格分隔），按词数从多到少匹配，取最长的完整匹配。 */
    static final List<String> PLAYER_OPERATORS = Collections.unmodifiableList(Arrays.asList(
            "locale", "world", "x", "y", "z", "yaw", "pitch", "block x", "block y", "block z",
            "compass x", "compass y", "compass z", "location", "compass target", "bed spawn",
            "bed spawn x", "bed spawn y", "bed spawn z", "name", "list name", "display name", "uuid",
            "gamemode", "address", "sneaking", "sprinting", "blocking", "gliding", "glowing", "swimming",
            "riptiding", "sleeping", "sleep ticks", "sleep ignored", "dead", "conversing", "leashed",
            "on ground", "is online", "inside vehicle", "op", "gravity", "attack cooldown", "player time",
            "first played", "last played", "absorption amount", "no damage ticks", "remaining air",
            "maximum air", "exp", "level", "exhaustion", "saturation", "food level", "health",
            "max health", "allow flight", "flying", "fly speed", "walk speed", "ping", "pose", "facing"));

    /** 读取玩家操作名；不在原框架表中时退回单个词元（宿主自定义属性）。 */
    private static String playerOperator(QuestReader reader) {
        requireInput(reader);
        int start = reader.getIndex();
        String best = null;
        int bestEnd = start;
        for (String operator : PLAYER_OPERATORS) {
            String[] words = operator.split(" ");
            if (best != null && words.length <= best.split(" ").length) continue;
            reader.setIndex(start);
            boolean matched = true;
            for (String word : words) {
                if (!reader.hasNext() || reader.peek() == '}' || reader.peek() == ']' || !word.equals(reader.nextToken())) {
                    matched = false;
                    break;
                }
            }
            if (matched) { best = operator; bestEnd = reader.getIndex(); }
        }
        if (best != null) { reader.setIndex(bestEnd); return best; }
        reader.setIndex(start);
        return reader.nextToken();
    }

    private static Object player(ScriptContext context) {
        Object sender = context.sender().orElse(null);
        if (sender == null || !context.requireService(ScriptSenderQuery.class).isPlayer(sender)) {
            throw new IllegalStateException("No player selected.");
        }
        return sender;
    }

    private static Object placeholder(QuestContext.Frame frame, String text) {
        ScriptContext context = CoreScriptRuntime.context(frame);
        Object sender = player(context);
        return context.requireService(PlaceholderResolver.class).resolve(sender, text);
    }

    /** Kotlin trimIndent：删首尾空行、取非空行最小缩进，不裁剪行尾空白。 */
    static String trimIndent(String text) {
        String[] lines = text.split("\\r\\n|\\n|\\r", -1);
        int indent = Integer.MAX_VALUE;
        for (String line : lines) {
            if (!blank(line)) {
                int count = 0;
                while (count < line.length() && whitespace(line.charAt(count))) count++;
                indent = Math.min(indent, count);
            }
        }
        if (indent == Integer.MAX_VALUE) indent = 0;
        StringBuilder result = new StringBuilder();
        boolean first = true;
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            if ((index == 0 || index == lines.length - 1) && blank(line)) continue;
            if (!first) result.append('\n');
            first = false;
            result.append(line.substring(Math.min(indent, line.length())));
        }
        return result.toString();
    }

    private static boolean blank(String line) {
        for (int index = 0; index < line.length(); index++) if (!whitespace(line.charAt(index))) return false;
        return true;
    }
    private static boolean whitespace(char value) { return Character.isWhitespace(value) || Character.isSpaceChar(value); }

    private static ParsedAction<?> requiredValue(QuestReader reader) { requireInput(reader); return reader.nextValue(); }
    private static String requiredToken(QuestReader reader) { requireInput(reader); return reader.nextToken(); }
    private static void requireInput(QuestReader reader) {
        if (!reader.hasNext() || reader.peek() == '}' || reader.peek() == ']') {
            throw new IllegalArgumentException("Expected a check/query argument before end of input or closing delimiter");
        }
    }
    private static QuestActionParser constant(Object value) { return QuestActionParser.of(reader -> action(frame -> completed(value))); }
    private static QuestAction<Object> action(Function<QuestContext.Frame, CompletableFuture<Object>> operation) {
        return new QuestAction<Object>() {
            @Override public CompletableFuture<Object> process(QuestContext.Frame frame) { return operation.apply(frame); }
        };
    }
    private static CompletableFuture<Object> completed(Object value) { return CompletableFuture.completedFuture(value); }
    private static <T, R> CompletableFuture<R> follow(QuestContext.Frame frame, CompletableFuture<T> input,
            Function<T, CompletableFuture<R>> next) {
        return input.isDone() ? input.thenCompose(next) : input.thenComposeAsync(next, frame.context().getExecutor());
    }

    private static Object infer(Object value) {
        if (!(value instanceof String)) return value;
        String text = (String) value;
        try { return Integer.valueOf(text); } catch (NumberFormatException ignored) { }
        try { return Long.valueOf(text); } catch (NumberFormatException ignored) { }
        try { return Double.valueOf(text); } catch (NumberFormatException ignored) { }
        if ("true".equals(text)) return Boolean.TRUE;
        if ("false".equals(text)) return Boolean.FALSE;
        return value;
    }
    private static double number(Object value) {
        if (value == null) return 0.0;
        if (value instanceof Number) return ((Number) value).doubleValue();
        String text = sanitized(value);
        if (!FLOAT.matcher(text).matches()) return 0.0;
        try { return Double.parseDouble(text); } catch (NumberFormatException ignored) { return 0.0; }
    }
    private static String sanitized(Object value) {
        String text = value.toString().trim();
        if (text.isEmpty()) return "0";
        Matcher match = BRACKETS.matcher(text);
        if (match.matches() && "([{".indexOf(match.group(1)) == ")]}".indexOf(match.group(3))) text = match.group(2).trim();
        int decimal = text.indexOf('.'), comma = text.indexOf(',', decimal);
        if (decimal > -1 && comma > -1) return sanitized(text.substring(0, comma));
        if (text.indexOf('-', 1) != -1) return "0";
        return text.replace(",", "").split(" ")[0];
    }
    private static boolean contains(Object container, Object value) {
        if (container instanceof Collection<?>) return ((Collection<?>) container).contains(value);
        if (container instanceof Object[]) return Arrays.asList((Object[]) container).contains(value);
        if (container instanceof Map<?, ?>) return ((Map<?, ?>) container).containsKey(value);
        return String.valueOf(container).contains(String.valueOf(value));
    }
    private static final class Outcome {
        private final Object value;
        private final Throwable failure;
        private Outcome(Object value, Throwable failure) { this.value = value; this.failure = failure; }
    }
    private enum Operator {
        EQUAL, NOT_EQUAL, STRICT_EQUAL, MEMORY_EQUAL, IGNORE_CASE, GT, GTE, LT, LTE, CONTAINS, IN;
        static Operator of(String value) {
            switch (value) {
                // '=' 是已公开 Klib 的兼容扩展，不属于原 CheckType alias 表。
                case "=": case "==": case "is": return EQUAL;
                case "!=": case "!is": case "not": return NOT_EQUAL;
                case "=!": case "is!": return STRICT_EQUAL;
                case "=!!": case "is!!": return MEMORY_EQUAL;
                case "=?": case "is?": return IGNORE_CASE;
                case ">": case "gt": return GT;
                case ">=": case "gte": return GTE;
                case "<": case "lt": return LT;
                case "<=": case "lte": return LTE;
                case "contains": case "has": return CONTAINS;
                case "in": return IN;
                default: throw new IllegalArgumentException("Unknown check operator: " + value);
            }
        }
        boolean check(Object left, Object right) {
            switch (this) {
                case EQUAL:
                    Object first = infer(left), second = infer(right);
                    return first instanceof Number && second instanceof Number
                            ? ((Number) first).doubleValue() == ((Number) second).doubleValue() : Objects.equals(first, second);
                case NOT_EQUAL: return !EQUAL.check(left, right);
                case STRICT_EQUAL: return Objects.equals(left, right);
                case MEMORY_EQUAL: return left == right;
                case IGNORE_CASE: return String.valueOf(left).equalsIgnoreCase(String.valueOf(right));
                case GT: return number(left) > number(right);
                case GTE: return number(left) >= number(right);
                case LT: return number(left) < number(right);
                case LTE: return number(left) <= number(right);
                case CONTAINS: return contains(left, right);
                case IN: return contains(right, left);
                default: throw new AssertionError(this);
            }
        }
    }
}
