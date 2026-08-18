package me.kzheart.klib.script;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** 传递给动作解析器的已解析语句。 */
public final class StatementCall {

    interface NestedEvaluator {
        CompletionStage<Object> eval(String source, ScriptContext context);
    }

    private final String name;
    private final List<String> arguments;
    private final int line;
    private final int column;
    private final NestedEvaluator nestedEvaluator;

    StatementCall(
            String name,
            List<String> arguments,
            int line,
            int column,
            NestedEvaluator nestedEvaluator
    ) {
        this.name = name;
        this.arguments = Collections.unmodifiableList(arguments);
        this.line = line;
        this.column = column;
        this.nestedEvaluator = nestedEvaluator;
    }

    public String name() {
        return name;
    }

    public List<String> arguments() {
        return arguments;
    }

    public String argument(int index) {
        if (index < 0 || index >= arguments.size()) {
            throw new IllegalArgumentException("Missing statement argument at index " + index);
        }
        return arguments.get(index);
    }

    public Object value(int index, ScriptContext context) {
        return InlineValues.value(argument(index), context);
    }

    public String text(int startIndex, ScriptContext context) {
        if (startIndex < 0 || startIndex > arguments.size()) {
            throw new IllegalArgumentException("Invalid statement argument index " + startIndex);
        }
        StringBuilder result = new StringBuilder();
        for (int index = startIndex; index < arguments.size(); index++) {
            if (result.length() > 0) {
                result.append(' ');
            }
            result.append(InlineValues.text(arguments.get(index), context));
        }
        return result.toString();
    }

    public int line() {
        return line;
    }

    public int column() {
        return column;
    }

    public CompletionStage<Object> eval(String source, ScriptContext context) {
        return nestedEvaluator.eval(Objects.requireNonNull(source, "source"), context);
    }
}
