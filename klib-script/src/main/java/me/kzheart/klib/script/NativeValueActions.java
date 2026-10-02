/* Kether: Copyright (c) 2018 Bkm016. Coerce: Copyright SpongePowered and contributors.
 * MIT License. See THIRD_PARTY_NOTICES.md. */
package me.kzheart.klib.script;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import me.kzheart.klib.script.kether.core.ParsedAction;
import me.kzheart.klib.script.kether.core.QuestAction;
import me.kzheart.klib.script.kether.core.QuestActionParser;
import me.kzheart.klib.script.kether.core.QuestContext;
import me.kzheart.klib.script.kether.core.QuestReader;
import me.kzheart.klib.script.kether.core.SimpleReader;

/** 按动作树求值的通用数值与赋值动作；不使用另一套表达式解释器。 */
final class NativeValueActions {
    private static final Pattern BRACKETS = Pattern.compile("^([(\\[{]?)(.+?)([)\\]}]?)$");
    private NativeValueActions() { }

    static void install(StatementRegistry registry) {
        registry.registerBuiltinKether("math", QuestActionParser.of(NativeValueActions::math));
        registry.registerBuiltinKether("round", unary(value -> {
            double number = number(value);
            if (Double.isNaN(number)) throw new IllegalArgumentException("Cannot round NaN value.");
            long rounded = Math.round(number);
            return Integer.valueOf((int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, rounded)));
        }));
        QuestActionParser random = QuestActionParser.of(NativeValueActions::random);
        registry.registerBuiltinKether("random", random);
        registry.registerBuiltinKether("random2", random);
        registry.registerBuiltinKether("set", QuestActionParser.of(NativeValueActions::set));
        registry.registerBuiltinKether("permission", QuestActionParser.of(reader -> {
            ParsedAction<?> permission = requiredValue(reader);
            return action(frame -> follow(frame, frame.newFrame(permission).run(), value -> {
                ScriptContext context = CoreScriptRuntime.context(frame);
                Object sender = context.sender().orElse(null);
                if (sender == null || !context.requireService(ScriptSenderQuery.class).isPlayer(sender)) {
                    throw new IllegalStateException("No player selected.");
                }
                return completed(Boolean.valueOf(context.requireService(PlayerQuery.class)
                        .hasPermission(sender, text(value))));
            }));
        }));
        registry.registerBuiltinKether("sender", QuestActionParser.of(reader -> action(frame -> {
            ScriptContext context = CoreScriptRuntime.context(frame);
            Object sender = context.sender().orElse(null);
            if (sender == null) return completed("console");
            ScriptSenderQuery query = context.requireService(ScriptSenderQuery.class);
            // 原 ProxyCommandSender?.isConsole() 为“不是 ProxyPlayer”，也包括命令方块。
            return completed(!query.isPlayer(sender)
                    ? "console" : String.valueOf(query.name(sender)));
        })));
    }

    private static QuestActionParser unary(Function<Object, Object> operation) {
        return QuestActionParser.of(reader -> {
            ParsedAction<?> input = requiredValue(reader);
            return action(frame -> follow(frame, frame.newFrame(input).run(), value -> completed(operation.apply(value))));
        });
    }

    private static QuestAction<Object> math(QuestReader reader) {
        int start = reader.getIndex();
        Arithmetic arithmetic = Arithmetic.of(reader.nextToken());
        if (arithmetic != null) {
            reader.expect("[");
            List<ParsedAction<?>> inputs = new ArrayList<ParsedAction<?>>();
            while (!StructuredScriptActions.consume(reader, "]")) inputs.add(requiredValue(reader));
            return action(frame -> collect(frame, inputs, 0, new ArrayList<Object>(), arithmetic));
        }
        reader.setIndex(start);
        List<ParsedAction<?>> inputs = new ArrayList<ParsedAction<?>>();
        List<Arithmetic> operations = new ArrayList<Arithmetic>();
        inputs.add(requiredValue(reader));
        while (reader.hasNext()) {
            int mark = reader.getIndex();
            try {
                Arithmetic symbol = Arithmetic.of(reader.nextToken());
                if (symbol == null) { reader.setIndex(mark); break; }
                ParsedAction<?> next = requiredValue(reader);
                operations.add(symbol);
                inputs.add(next);
            } catch (RuntimeException failure) {
                if (failure instanceof MissingValueException) throw failure;
                reader.setIndex(mark);
                break;
            }
        }
        if (inputs.size() == 1) throw new IllegalArgumentException("math requires an operator and another action");
        return action(frame -> follow(frame, frame.newFrame(inputs.get(0)).run(), value -> {
            Object inferred = infer(value);
            if (!(inferred instanceof Number)) throw new ClassCastException("math base is not a Number");
            return infix(frame, inputs, operations, 1, (Number) inferred);
        }));
    }

    private static CompletableFuture<Object> collect(QuestContext.Frame frame, List<ParsedAction<?>> inputs,
            int index, List<Object> values, Arithmetic arithmetic) {
        if (index == inputs.size()) return completed(arithmetic.apply(values));
        return follow(frame, frame.newFrame(inputs.get(index)).run(), value -> {
            values.add(value);
            return collect(frame, inputs, index + 1, values, arithmetic);
        }).exceptionally(failure -> { failure.printStackTrace(); return Integer.valueOf(0); });
    }

    private static CompletableFuture<Object> infix(QuestContext.Frame frame, List<ParsedAction<?>> inputs,
            List<Arithmetic> operations, int index, Number current) {
        if (index == inputs.size()) return completed(current);
        return follow(frame, frame.newFrame(inputs.get(index)).run(), value -> {
            List<Object> pair = new ArrayList<Object>();
            pair.add(current); pair.add(value);
            return infix(frame, inputs, operations, index + 1, operations.get(index - 1).apply(pair));
        }).exceptionally(failure -> { failure.printStackTrace(); return Integer.valueOf(0); });
    }

    private static QuestAction<Object> random(QuestReader reader) {
        ParsedAction<?> from = requiredValue(reader);
        int mark = reader.getIndex();
        try {
            reader.expect("to");
            ParsedAction<?> to = requiredValue(reader);
            return action(frame -> follow(frame, frame.newFrame(from).run(), left ->
                    follow(frame, frame.newFrame(to).run(), right -> completed(randomRange(text(left), text(right))))));
        } catch (RuntimeException ignored) {
            if (ignored instanceof MissingValueException) throw ignored;
            reader.setIndex(mark);
            return action(frame -> follow(frame, frame.newFrame(from).run(), value -> completed(randomValue(value))));
        }
    }

    private static Object randomRange(String from, String to) {
        if (isInt(from) && isInt(to)) {
            int first = integer(from), second = integer(to);
            return Integer.valueOf(ThreadLocalRandom.current().nextInt(Math.min(first, second), Math.max(first, second) + 1));
        }
        double first = number(from), second = number(to);
        double min = Math.min(first, second), max = Math.max(first, second);
        return Double.valueOf(min == max ? max : ThreadLocalRandom.current().nextDouble(min, max));
    }

    private static Object randomValue(Object value) {
        if (value instanceof Collection<?>) {
            List<?> items = new ArrayList<Object>((Collection<?>) value);
            for (Object item : items) requireElement(item);
            return items.isEmpty() ? null : requireElement(items.get(ThreadLocalRandom.current().nextInt(items.size())));
        }
        if (value instanceof Object[]) {
            Object[] items = (Object[]) value;
            // 原 Kotlin i as Any 在选择前遍历，任何 null 元素都会失败。
            for (Object item : items) requireElement(item);
            return items.length == 0 ? null : items[ThreadLocalRandom.current().nextInt(items.length)];
        }
        if (value != null && isInt(value)) return Integer.valueOf(ThreadLocalRandom.current().nextInt(integer(value)));
        return randomRange("0.0", String.valueOf(number(value)));
    }

    private static Object requireElement(Object value) {
        if (value == null) throw new NullPointerException("null cannot be cast to non-null type kotlin.Any");
        return value;
    }

    private static QuestAction<Object> set(QuestReader reader) {
        requireInput(reader);
        boolean quotedKey = reader.peek() == '\'' || reader.peek() == '"';
        String key = requiredToken(reader);
        int bracket = key.indexOf('[');
        if (key.endsWith("]") && bracket > 0) {
            if (quotedKey) throw new ClassCastException("Literal action is not a property getter");
            ParsedAction<?> source = propertySource(reader, key.substring(0, bracket));
            String property = key.substring(bracket + 1, key.length() - 1);
            return propertySet(source, property, assignmentValue(reader));
        }
        if ("property".equals(key)) {
            int mark = reader.getIndex();
            try {
                String property = reader.nextToken();
                reader.expect("from");
                ParsedAction<?> source = requiredValue(reader);
                reader.expect("to");
                return propertySet(source, property, requiredValue(reader));
            } catch (RuntimeException ignored) {
                if (ignored instanceof MissingValueException) throw ignored;
                reader.setIndex(mark);
            }
        }
        int mark = reader.getIndex();
        try {
            reader.expect("to");
            ParsedAction<?> value = requiredValue(reader);
            return action(frame -> follow(frame, frame.newFrame(value).run(), result -> {
                frame.variables().set(key, result);
                return completed(null);
            }).exceptionally(failure -> { failure.printStackTrace(); return null; }));
        } catch (RuntimeException ignored) {
            if (ignored instanceof MissingValueException) throw ignored;
            reader.setIndex(mark);
            String value = requiredToken(reader);
            // 保留已公开 Klib set key value 的取值/返回值约定；to 分支采用 ParsedAction。
            return action(frame -> {
                Object result = InlineValues.value(value, CoreScriptRuntime.context(frame));
                CoreScriptRuntime.context(frame).setVariable(key, result);
                return completed(result);
            });
        }
    }

    private static ParsedAction<?> assignmentValue(QuestReader reader) {
        int mark = reader.getIndex();
        try { reader.expect("to"); return requiredValue(reader); }
        catch (RuntimeException ignored) {
            if (ignored instanceof MissingValueException) throw ignored;
            reader.setIndex(mark);
            return new ParsedAction<Object>(literal(requiredToken(reader)));
        }
    }

    private static ParsedAction<?> propertySource(QuestReader reader, String source) {
        if (source.startsWith("&")) {
            String variable = source.substring(1);
            return new ParsedAction<Object>(action(frame -> completed(frame.variables().getOrNull(variable))));
        }
        if (source.startsWith("*")) throw new ClassCastException("Literal action is not a property getter");
        if (!(reader instanceof SimpleReader)) throw new IllegalArgumentException("Property shorthand requires a native QuestReader");
        SimpleReader nativeReader = (SimpleReader) reader;
        Optional<QuestActionParser> parser = nativeReader.getService().getRegistry().getParser(source, nativeReader.getNamespace());
        if (parser.isPresent()) return new ParsedAction<Object>(parser.get().resolve(reader));
        if (nativeReader.getService().isToleranceParser()) return new ParsedAction<Object>(literal(source));
        throw new IllegalArgumentException("Unknown property source action: " + source);
    }

    private static QuestAction<Object> propertySet(ParsedAction<?> source, String property, ParsedAction<?> value) {
        return action(frame -> follow(frame, frame.newFrame(source).run(), instance -> {
            if (instance == null) throw new IllegalArgumentException("Property object must be not null.");
            return follow(frame, frame.newFrame(value).run(), result -> {
                if (!ScriptProperties.write(frame, instance, property, result)) {
                    throw new IllegalArgumentException(instance.getClass().getSimpleName() + "[" + property + "] not supported yet.");
                }
                return completed(null);
            });
        }));
    }

    private static ParsedAction<?> requiredValue(QuestReader reader) {
        requireInput(reader);
        return reader.nextValue();
    }
    private static String requiredToken(QuestReader reader) {
        requireInput(reader);
        return reader.nextToken();
    }
    private static void requireInput(QuestReader reader) {
        if (!reader.hasNext() || reader.peek() == '}' || reader.peek() == ']') {
            throw new MissingValueException();
        }
    }
    private static final class MissingValueException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        private MissingValueException() { super("Expected a value action before end of input or closing delimiter"); }
    }

    private static QuestAction<Object> literal(Object value) { return action(frame -> completed(value)); }
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
    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private static boolean isInt(Object value) {
        if (value == null) throw new NullPointerException("isInt requires a non-null value");
        try { Integer.parseInt(String.valueOf(value)); return true; }
        catch (RuntimeException ignored) { return false; }
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
    private static int integer(Object value) {
        if (value instanceof Number) return ((Number) value).intValue();
        String text = sanitized(value);
        try { return Integer.parseInt(text); }
        catch (NumberFormatException ignored) { return (int) number(text); }
    }
    private static double number(Object value) {
        if (value instanceof Number) return ((Number) value).doubleValue();
        try { return Double.parseDouble(sanitized(value)); }
        catch (NumberFormatException ignored) { return 0.0; }
    }
    private static String sanitized(Object value) {
        if (value == null) return "0";
        String text = value.toString().trim();
        if (text.isEmpty()) return "0";
        Matcher match = BRACKETS.matcher(text);
        if (match.matches() && "([{".indexOf(match.group(1)) == ")]}".indexOf(match.group(3))) text = match.group(2).trim();
        int decimal = text.indexOf('.'), comma = text.indexOf(',', decimal);
        if (decimal > -1 && comma > -1) return sanitized(text.substring(0, comma));
        if (text.indexOf('-', 1) != -1) return "0";
        return text.replace(",", "").split(" ")[0];
    }

    private enum Arithmetic {
        ADD, SUB, MUL, DIV;
        static Arithmetic of(String value) {
            switch (value) {
                case "add": case "+": return ADD;
                case "sub": case "-": return SUB;
                case "mul": case "*": return MUL;
                case "div": case "/": return DIV;
                default: return null;
            }
        }
        Number apply(List<Object> values) {
            boolean ints = true;
            for (Object value : values) if (!(value instanceof Integer) && !isInt(value)) { ints = false; break; }
            if (ints) {
                int current = this == MUL ? 1 : 0;
                for (int index = 0; index < values.size(); index++) {
                    int value = integer(values.get(index));
                    switch (this) {
                        case ADD: current += value; break;
                        case SUB: current = index == 0 ? value : current - value; break;
                        case MUL: current *= value; break;
                        case DIV: current = index == 0 ? value : current / value; break;
                        default: throw new AssertionError(this);
                    }
                }
                return Integer.valueOf(current);
            }
            double current = this == MUL ? 1.0 : 0.0;
            for (int index = 0; index < values.size(); index++) {
                double value = number(values.get(index));
                switch (this) {
                    case ADD: current += value; break;
                    case SUB: current = index == 0 ? value : current - value; break;
                    case MUL: current *= value; break;
                    case DIV: current = index == 0 ? value : current / value; break;
                    default: throw new AssertionError(this);
                }
            }
            return Double.valueOf(current);
        }
    }
}
