package me.kzheart.klib.script;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 位置组合解析器的 Java 构建器。 */
public final class Statements {

    private Statements() {
    }

    public static Combination combine() {
        return new Combination();
    }

    /** 按声明顺序定义必需参数、可选参数和剩余参数。 */
    public static final class Combination {

        private final List<Part> parts = new ArrayList<Part>();
        private boolean remainingDeclared;

        private Combination() {
        }

        public Combination required(String name) {
            ensureCanAppend();
            parts.add(new Part(requireName(name), null, false));
            return this;
        }

        public Combination optional(String name, String defaultValue) {
            ensureCanAppend();
            parts.add(new Part(requireName(name), Objects.requireNonNull(defaultValue, "defaultValue"), false));
            return this;
        }

        public Combination remaining(String name) {
            ensureCanAppend();
            parts.add(new Part(requireName(name), "", true));
            remainingDeclared = true;
            return this;
        }

        public QuestActionParser execute(final CombinedAction action) {
            Objects.requireNonNull(action, "action");
            final List<Part> definition = new ArrayList<Part>(parts);
            return (call, context) -> action.execute(parse(definition, call, context), context);
        }

        private void ensureCanAppend() {
            if (remainingDeclared) {
                throw new IllegalStateException("No argument may follow remaining()");
            }
        }

        private static StatementArguments parse(
                List<Part> definition,
                StatementCall call,
                ScriptContext context
        ) {
            Map<String, String> values = new LinkedHashMap<String, String>();
            int index = 0;
            for (Part part : definition) {
                if (part.remaining) {
                    values.put(part.name, call.text(index, context));
                    index = call.arguments().size();
                } else if (index < call.arguments().size()) {
                    values.put(part.name, call.argument(index++));
                } else if (part.defaultValue != null) {
                    values.put(part.name, part.defaultValue);
                } else {
                    throw new IllegalArgumentException(
                            ScriptMessages.text(context.locale(), "missing-argument", part.name));
                }
            }
            if (index < call.arguments().size()) {
                throw new IllegalArgumentException(
                        "Unexpected argument: " + call.argument(index));
            }
            return new StatementArguments(values);
        }

        private static String requireName(String name) {
            Objects.requireNonNull(name, "name");
            String normalized = name.trim();
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException("Argument name must not be blank");
            }
            return normalized;
        }
    }

    private static final class Part {
        private final String name;
        private final String defaultValue;
        private final boolean remaining;

        private Part(String name, String defaultValue, boolean remaining) {
            this.name = name;
            this.defaultValue = defaultValue;
            this.remaining = remaining;
        }
    }
}
