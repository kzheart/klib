package me.kzheart.klib.script;

import java.util.List;
import java.util.concurrent.CompletionStage;

/** 已解析语句的 Java 返回契约；参数和嵌套求值由 Kotlin 实现。 */
public final class StatementCall {
    interface NestedEvaluator { CompletionStage<Object> eval(String source, ScriptContext context); }
    private final StatementCallValues state;
    StatementCall(String name, List<String> arguments, int line, int column, NestedEvaluator evaluator) {
        state = new StatementCallValues(name, arguments, line, column, evaluator);
    }
    public String name() { return state.name(); }
    public List<String> arguments() { return state.arguments(); }
    public String argument(int index) { return state.argument(index); }
    public Object value(int index, ScriptContext context) { return state.value(index, context); }
    public String text(int startIndex, ScriptContext context) { return state.text(startIndex, context); }
    public int line() { return state.line(); }
    public int column() { return state.column(); }
    public CompletionStage<Object> eval(String source, ScriptContext context) { return state.eval(source, context); }
}
