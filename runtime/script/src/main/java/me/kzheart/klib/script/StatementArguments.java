package me.kzheart.klib.script;

import java.util.Map;
import java.util.Optional;

/** 参数求值保持 Java 平台返回类型；解析和值快照由 Kotlin 实现。 */
public final class StatementArguments {
    private final StatementArgumentValues state;
    StatementArguments(Map<String, String> values) { state = new StatementArgumentValues(values); }
    public String require(String name) { return state.require(name); }
    public Optional<String> get(String name) { return state.get(name); }
    public Object value(String name, ScriptContext context) { return state.value(name, context); }
    public Map<String, String> asMap() { return state.asMap(); }
}
