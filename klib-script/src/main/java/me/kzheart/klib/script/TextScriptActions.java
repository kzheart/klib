/* Kether action semantics: Copyright (c) 2018 Bkm016, MIT. See THIRD_PARTY_NOTICES.md. */
package me.kzheart.klib.script;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import me.kzheart.klib.script.kether.core.ParsedAction;
import me.kzheart.klib.script.kether.core.QuestActionParser;

import static me.kzheart.klib.script.KetherSupport.action;
import static me.kzheart.klib.script.KetherSupport.completed;
import static me.kzheart.klib.script.KetherSupport.consume;
import static me.kzheart.klib.script.KetherSupport.expects;
import static me.kzheart.klib.script.KetherSupport.follow;
import static me.kzheart.klib.script.KetherSupport.longValue;
import static me.kzheart.klib.script.KetherSupport.now;
import static me.kzheart.klib.script.KetherSupport.number;
import static me.kzheart.klib.script.KetherSupport.run;
import static me.kzheart.klib.script.KetherSupport.text;

/** 文本格式化、正则匹配与当前时间。 */
final class TextScriptActions {
    private static final Pattern COLOR_CODE = Pattern.compile("(?i)§[0-9A-FK-ORX]");

    private TextScriptActions() { }

    static void install(StatementRegistry registry) {
        QuestActionParser uncolored = unary(value -> uncolored(text(value)));
        registry.registerBuiltinKether("uncolor", uncolored);
        registry.registerBuiltinKether("uncolored", uncolored);
        // 原 Coerce.format：保留两位小数，HALF_UP。
        QuestActionParser scale = unary(value -> Double.valueOf(BigDecimal.valueOf(number(value)).setScale(2, RoundingMode.HALF_UP).doubleValue()));
        registry.registerBuiltinKether("scale", scale);
        registry.registerBuiltinKether("scaled", scale);
        registry.registerBuiltinKether("format", withOption((value, pattern) ->
                new SimpleDateFormat(pattern == null ? "yyyy/MM/dd HH:mm" : pattern).format(new Date(longValue(value)))));
        registry.registerBuiltinKether("printed", withOption((value, separator) -> printed(text(value), separator == null ? "_" : separator)));
        registry.registerBuiltinKether("match", QuestActionParser.of(reader -> {
            ParsedAction<?> source = reader.nextValue();
            expects(reader, "by", "with", "using");
            ParsedAction<?> pattern = reader.nextValue();
            return action(frame -> follow(frame, run(frame, source), input -> follow(frame, run(frame, pattern), regex -> {
                Matcher matcher = Pattern.compile(text(regex), Pattern.CASE_INSENSITIVE).matcher(text(input));
                matcher.find();
                return completed(matcher);
            })));
        }));
        QuestActionParser time = QuestActionParser.of(reader -> {
            String format = consume(reader, "as") ? reader.nextToken() : null;
            return now(frame -> format == null ? (Object) Long.valueOf(System.currentTimeMillis())
                    : new SimpleDateFormat(format).format(new Date()));
        });
        registry.registerBuiltinKether("time", time);
        registry.registerBuiltinKether("date", time);
        QuestActionParser day = QuestActionParser.of(reader -> {
            expects(reader, "of", "in");
            String unit = expects(reader, "year", "month", "week");
            return now(frame -> {
                LocalDateTime current = LocalDateTime.now();
                switch (unit) {
                    case "year": return Long.valueOf(current.getDayOfYear());
                    case "month": return Long.valueOf(current.getDayOfMonth());
                    default: return Long.valueOf(current.getDayOfWeek().getValue());
                }
            });
        });
        registry.registerBuiltinKether("day", day);
        registry.registerBuiltinKether("days", day);
        clock(registry, () -> Long.valueOf(LocalDateTime.now().getYear()), "year", "years");
        clock(registry, () -> Long.valueOf(LocalDateTime.now().getMonthValue()), "month", "months");
        clock(registry, () -> Long.valueOf(LocalDateTime.now().getHour()), "hour", "hours");
        clock(registry, () -> Long.valueOf(LocalDateTime.now().getMinute()), "minute", "minutes");
        clock(registry, () -> Long.valueOf(LocalDateTime.now().getSecond()), "second", "seconds");
    }

    /** 原框架 uncolored：先把 &amp; 颜色代码转为 §，再移除全部颜色与格式代码。 */
    static String uncolored(String text) {
        return COLOR_CODE.matcher(StructuredScriptActions.color(text)).replaceAll("");
    }

    /** 打字机效果：逐字生成前缀，奇数位置附加分隔符，跳过 § 颜色代码。 */
    static List<Object> printed(String text, String separator) {
        List<Object> result = new ArrayList<Object>();
        int index = 0;
        while (index < text.length()) {
            if (text.charAt(index) == '§') index++;
            else result.add(text.substring(0, index + 1) + (index % 2 == 1 ? separator : ""));
            index++;
        }
        if (!separator.isEmpty() && index % 2 == 0) result.add(text);
        return result;
    }

    private static void clock(StatementRegistry registry, Supplier<Object> value, String... names) {
        QuestActionParser parser = QuestActionParser.of(reader -> now(frame -> value.get()));
        for (String name : names) registry.registerBuiltinKether(name, parser);
    }

    private static QuestActionParser unary(Function<Object, Object> operation) {
        return QuestActionParser.of(reader -> {
            ParsedAction<?> input = reader.nextValue();
            return action(frame -> follow(frame, run(frame, input), value -> completed(operation.apply(value))));
        });
    }

    /** 语句 值 [by|with 文本]。 */
    private static QuestActionParser withOption(Option operation) {
        return QuestActionParser.of(reader -> {
            ParsedAction<?> input = reader.nextValue();
            ParsedAction<?> option = consume(reader, "by", "with") ? reader.nextValue() : null;
            return action(frame -> follow(frame, run(frame, input), value -> option == null
                    ? completed(operation.apply(value, null))
                    : follow(frame, run(frame, option), extra -> completed(operation.apply(value, text(extra))))));
        });
    }

    private interface Option {
        Object apply(Object value, String option);
    }
}
