/* Kether action semantics: Copyright (c) 2018 Bkm016, MIT. See THIRD_PARTY_NOTICES.md. */
package me.kzheart.klib.script;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Pattern;
import me.kzheart.klib.script.kether.core.ArgTypes;
import me.kzheart.klib.script.kether.core.ParsedAction;
import me.kzheart.klib.script.kether.core.QuestAction;
import me.kzheart.klib.script.kether.core.QuestActionParser;
import me.kzheart.klib.script.kether.core.QuestContext;
import me.kzheart.klib.script.kether.core.QuestReader;

import static me.kzheart.klib.script.KetherSupport.action;
import static me.kzheart.klib.script.KetherSupport.asList;
import static me.kzheart.klib.script.KetherSupport.completed;
import static me.kzheart.klib.script.KetherSupport.consume;
import static me.kzheart.klib.script.KetherSupport.expects;
import static me.kzheart.klib.script.KetherSupport.follow;
import static me.kzheart.klib.script.KetherSupport.integer;
import static me.kzheart.klib.script.KetherSupport.number;
import static me.kzheart.klib.script.KetherSupport.run;
import static me.kzheart.klib.script.KetherSupport.takeBreak;
import static me.kzheart.klib.script.KetherSupport.text;

/** 列表构建与读写、字符串拆分拼接和数值范围。 */
final class CollectionScriptActions {
    private CollectionScriptActions() { }

    static void install(StatementRegistry registry) {
        QuestActionParser size = unary(value -> {
            if (value instanceof Collection<?>) return Integer.valueOf(((Collection<?>) value).size());
            if (value != null && value.getClass().isArray()) return Integer.valueOf(Array.getLength(value));
            return Integer.valueOf(String.valueOf(value).length());
        });
        registry.registerBuiltinKether("size", size);
        registry.registerBuiltinKether("length", size);
        registry.registerBuiltinKether("mutable", unary(value -> new ArrayList<Object>(asList(value))));
        registry.registerBuiltinKether("shuffle", unary(value -> {
            List<Object> copy = new ArrayList<Object>(asList(value));
            Collections.shuffle(copy);
            return copy;
        }));
        registry.registerBuiltinKether("reverse", unary(value -> {
            List<Object> copy = new ArrayList<Object>(asList(value));
            Collections.reverse(copy);
            return copy;
        }));
        QuestActionParser array = QuestActionParser.of(reader -> {
            List<ParsedAction<?>> items = reader.next(ArgTypes.listOf(ArgTypes.ACTION));
            return action(frame -> collect(frame, items).thenApply(values -> (Object) values));
        });
        registry.registerBuiltinKether("array", array);
        registry.registerBuiltinKether("arr", array);
        QuestActionParser get = indexed(new String[]{"in", "of"}, (index, list) -> index >= 0 && index < list.size() ? list.get(index) : null);
        for (String name : new String[]{"arr-get", "element", "elem"}) registry.registerBuiltinKether(name, get);
        registry.registerBuiltinKether("arr-add", element(new String[]{"to"}, (value, list) -> Boolean.valueOf(list.add(value))));
        QuestActionParser addFirst = element(new String[]{"to"}, (value, list) -> { list.add(0, value); return null; });
        registry.registerBuiltinKether("arr-add-first", addFirst);
        registry.registerBuiltinKether("arr-push", addFirst);
        registry.registerBuiltinKether("arr-remove", element(new String[]{"in"}, (value, list) -> Boolean.valueOf(list.remove(value))));
        registry.registerBuiltinKether("arr-remove-at", indexed(new String[]{"in"}, (index, list) -> list.remove(index.intValue())));
        QuestActionParser removeFirst = unary(value -> {
            List<Object> list = asList(value);
            return list.isEmpty() ? null : list.remove(0);
        });
        registry.registerBuiltinKether("arr-remove-first", removeFirst);
        registry.registerBuiltinKether("arr-take", removeFirst);
        QuestActionParser removeLast = unary(value -> {
            List<Object> list = asList(value);
            return list.isEmpty() ? null : list.remove(list.size() - 1);
        });
        registry.registerBuiltinKether("arr-remove-last", removeLast);
        registry.registerBuiltinKether("arr-drop", removeLast);
        registry.registerBuiltinKether("arr-find", element(new String[]{"in", "of"}, (value, list) -> Integer.valueOf(list.indexOf(value))));
        registry.registerBuiltinKether("split", QuestActionParser.of(reader -> {
            ParsedAction<?> source = reader.nextValue();
            ParsedAction<?> separator = consume(reader, "by", "with") ? reader.nextValue() : null;
            return action(frame -> follow(frame, run(frame, source), value -> {
                String text = text(value);
                if (separator == null) {
                    List<Object> chars = new ArrayList<Object>();
                    for (int index = 0; index < text.length(); index++) chars.add(String.valueOf(text.charAt(index)));
                    return completed(chars);
                }
                return follow(frame, run(frame, separator), regex -> {
                    List<Object> parts = new ArrayList<Object>();
                    // 与 Kotlin split(Regex) 一致，保留末尾空串。
                    Collections.addAll(parts, (Object[]) Pattern.compile(text(regex)).split(text, -1));
                    return completed(parts);
                });
            }));
        }));
        registry.registerBuiltinKether("range", QuestActionParser.of(reader -> {
            ParsedAction<?> from = reader.nextValue();
            reader.expect("to");
            ParsedAction<?> to = reader.nextValue();
            ParsedAction<?> step = consume(reader, "step") ? reader.nextValue() : null;
            return action(frame -> follow(frame, run(frame, from), start -> follow(frame, run(frame, to), end ->
                    step == null ? completed(range(number(start), number(end), 0.0))
                            : follow(frame, run(frame, step), increment -> completed(range(number(start), number(end), number(increment)))))));
        }));
    }

    /** join 的原框架列表写法：join [ 动作... ] [by|with 分隔符]，逐个求值，遇 break 提前结束。 */
    static QuestAction<Object> joinList(QuestReader reader) {
        List<ParsedAction<?>> items = reader.next(ArgTypes.listOf(ArgTypes.ACTION));
        ParsedAction<?> separator = consume(reader, "by", "with") ? reader.nextValue() : null;
        return action(frame -> {
            CompletableFuture<Object> delimiter = separator == null ? completed("") : run(frame, separator);
            return follow(frame, delimiter, value -> {
                CompletableFuture<Object> future = new CompletableFuture<Object>();
                joinNext(frame, items, 0, new ArrayList<Object>(), text(value), future);
                return future;
            });
        });
    }

    private static void joinNext(QuestContext.Frame frame, List<ParsedAction<?>> items, int index, List<Object> values,
                                 String separator, CompletableFuture<Object> future) {
        if (index >= items.size()) { future.complete(joined(values, separator)); return; }
        CompletableFuture<Object> input = run(frame, items.get(index));
        BiConsumer<Object, Throwable> continuation = (value, failure) -> {
            if (failure != null) { future.complete(""); return; }
            values.add(value);
            if (takeBreak(frame)) future.complete(joined(values, separator));
            else joinNext(frame, items, index + 1, values, separator, future);
        };
        CompletableFuture<Object> observed = input.isDone() ? input.whenComplete(continuation)
                : input.whenCompleteAsync(continuation, frame.context().getExecutor());
        observed.whenComplete((value, failure) -> {
            if (failure != null && !future.isDone()) future.completeExceptionally(ScriptFutures.unwrap(failure));
        });
    }

    private static String joined(List<Object> values, String separator) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) result.append(separator);
            result.append(values.get(index));
        }
        return result.toString();
    }

    private static List<Object> range(double from, double to, double step) {
        List<Object> result = new ArrayList<Object>();
        if (step == 0.0) {
            for (int value = (int) from; value <= (int) to; value++) result.add(Integer.valueOf(value));
            return result;
        }
        boolean intStep = (double) (int) step == step;
        for (double value = from; value <= to; value += step) result.add(intStep ? (Object) Integer.valueOf((int) value) : Double.valueOf(value));
        return result;
    }

    /** 顺序求值列表中的动作，结果为可变列表。 */
    static CompletableFuture<List<Object>> collect(QuestContext.Frame frame, List<ParsedAction<?>> items) {
        CompletableFuture<List<Object>> result = CompletableFuture.completedFuture(new ArrayList<Object>());
        for (ParsedAction<?> item : items) {
            result = follow(frame, result, values -> follow(frame, run(frame, item), value -> {
                values.add(value);
                return CompletableFuture.completedFuture(values);
            }));
        }
        return result;
    }

    private static QuestActionParser unary(Function<Object, Object> operation) {
        return QuestActionParser.of(reader -> {
            ParsedAction<?> input = reader.nextValue();
            return action(frame -> follow(frame, run(frame, input), value -> completed(operation.apply(value))));
        });
    }

    /** 形如 语句 值 关键字 列表 的写法。 */
    private static QuestActionParser element(String[] keywords, BiFunction<Object, List<Object>, Object> operation) {
        return QuestActionParser.of(reader -> {
            ParsedAction<?> value = reader.nextValue();
            expects(reader, keywords);
            ParsedAction<?> list = reader.nextValue();
            return action(frame -> follow(frame, run(frame, value), element -> follow(frame, run(frame, list), target ->
                    completed(operation.apply(element, asList(target))))));
        });
    }

    private static QuestActionParser indexed(String[] keywords, BiFunction<Integer, List<Object>, Object> operation) {
        return element(keywords, (value, list) -> operation.apply(Integer.valueOf(integer(value)), list));
    }
}
