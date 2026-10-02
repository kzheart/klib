/* Kether action semantics: Copyright (c) 2018 Bkm016. Coerce: Copyright SpongePowered and contributors.
 * MIT License. See THIRD_PARTY_NOTICES.md. */
package me.kzheart.klib.script;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import me.kzheart.klib.script.kether.core.ParsedAction;
import me.kzheart.klib.script.kether.core.QuestAction;
import me.kzheart.klib.script.kether.core.QuestContext;
import me.kzheart.klib.script.kether.core.QuestReader;

/** 原生 Kether 语句共用的执行与类型转换工具；转换规则沿用原框架 Coerce。 */
final class KetherSupport {
    static final String BREAK_LOOP = "~klib:break-loop";
    private static final Pattern BRACKETS = Pattern.compile("^([(\\[{]?)(.+?)([)\\]}]?)$");

    private KetherSupport() { }

    static QuestAction<Object> action(Function<QuestContext.Frame, CompletableFuture<Object>> operation) {
        return new QuestAction<Object>() {
            @Override public CompletableFuture<Object> process(QuestContext.Frame frame) { return operation.apply(frame); }
        };
    }

    static QuestAction<Object> now(Function<QuestContext.Frame, Object> operation) {
        return action(frame -> completed(operation.apply(frame)));
    }

    static CompletableFuture<Object> completed(Object value) { return CompletableFuture.completedFuture(value); }

    static CompletableFuture<Object> run(QuestContext.Frame frame, ParsedAction<?> action) {
        return frame.newFrame(action).run();
    }

    /** 仅异步恢复需要调度；同步动作不要求宿主提供 executor。 */
    static <T, R> CompletableFuture<R> follow(QuestContext.Frame frame, CompletableFuture<T> input,
                                             Function<T, CompletableFuture<R>> next) {
        return input.isDone() ? input.thenCompose(next) : input.thenComposeAsync(next, frame.context().getExecutor());
    }

    /** 读取可选关键字，未匹配时回退读取位置。 */
    static boolean consume(QuestReader reader, String... tokens) {
        int index = reader.getIndex();
        if (reader.hasNext()) {
            String token = reader.nextToken();
            for (String expected : tokens) if (expected.equals(token)) return true;
        }
        reader.setIndex(index);
        return false;
    }

    /** 读取必需关键字之一并返回匹配项。 */
    static String expects(QuestReader reader, String... tokens) {
        String token = reader.nextToken();
        for (String expected : tokens) if (expected.equals(token)) return token;
        throw new IllegalArgumentException("Expected one of " + String.join(", ", tokens) + ", got " + token);
    }

    static Object root(QuestContext.Frame frame, String key) {
        return frame.context().rootFrame().variables().getOrNull(key);
    }

    static void setRoot(QuestContext.Frame frame, String key, Object value) {
        if (value == null) frame.context().rootFrame().variables().remove(key);
        else frame.context().rootFrame().variables().set(key, value);
    }

    /** 读取并清除循环中断标记。 */
    static boolean takeBreak(QuestContext.Frame frame) {
        if (!Boolean.TRUE.equals(root(frame, BREAK_LOOP))) return false;
        setRoot(frame, BREAK_LOOP, null);
        return true;
    }

    /** 原 Coerce.toString：null 为空串。 */
    static String text(Object value) { return value == null ? "" : String.valueOf(value); }

    static boolean bool(Object value) {
        if (value == null) return false;
        if (value instanceof Boolean) return (Boolean) value;
        String text = value.toString().trim();
        return "1".equals(text) || "true".equalsIgnoreCase(text) || "yes".equalsIgnoreCase(text);
    }

    static int integer(Object value) {
        if (value == null) return 0;
        if (value instanceof Number) return ((Number) value).intValue();
        String text = sanitized(value);
        try { return Integer.parseInt(text); }
        catch (NumberFormatException ignored) { return (int) number(text); }
    }

    static long longValue(Object value) {
        if (value == null) return 0L;
        if (value instanceof Number) return ((Number) value).longValue();
        try { return Long.parseLong(sanitized(value)); }
        catch (NumberFormatException ignored) { return 0L; }
    }

    static double number(Object value) {
        if (value == null) return 0.0;
        if (value instanceof Number) return ((Number) value).doubleValue();
        try { return Double.parseDouble(sanitized(value)); }
        catch (NumberFormatException ignored) { return 0.0; }
    }

    static boolean isInt(Object value) {
        try { Integer.parseInt(String.valueOf(value)); return true; }
        catch (NumberFormatException ignored) { return false; }
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

    /** 原 anyAsList：可变列表原样返回（就地修改），其他值包装为单元素列表。 */
    @SuppressWarnings("unchecked")
    static List<Object> asList(Object value) {
        if (value instanceof List<?>) return (List<Object>) value;
        List<Object> result = new ArrayList<Object>();
        result.add(value);
        return result;
    }

    /** 循环语句的遍历源：集合、数组、Map 条目，其余值视为单元素。 */
    static List<Object> elements(Object value) {
        List<Object> result = new ArrayList<Object>();
        if (value instanceof Collection<?>) result.addAll((Collection<?>) value);
        else if (value instanceof Map<?, ?>) result.addAll(((Map<?, ?>) value).entrySet());
        else if (value != null && value.getClass().isArray()) {
            for (int index = 0; index < Array.getLength(value); index++) result.add(Array.get(value, index));
        } else result.add(value);
        return result;
    }

    static List<Object> unmodifiable(List<Object> values) { return Collections.unmodifiableList(values); }
}
