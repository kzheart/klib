package me.kzheart.klib.script;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import me.kzheart.klib.script.kether.core.QuestContext;

/** 宿主上下文的 Java 平台返回边界；变量、帧与服务由 Kotlin 状态实现。 */
public final class ScriptContext {
    static final String SENDER_OVERRIDE = "~klib:sender";
    private final ScriptContextState state;
    ScriptContext(ScriptContextState state) { this.state = state; }
    ScriptContext atFrame(QuestContext.Frame frame) { return state.atFrame(frame); }
    void writeVariable(String name, Object value) { state.writeVariable(name, value); }
    public static Builder builder() { return new Builder(); }
    public Optional<Object> sender() { return state.sender(); }
    public Optional<Object> variable(String name) { return state.variable(name); }
    public Object variableOrNull(String name) { return state.variableOrNull(name); }
    public void setVariable(String name, Object value) { state.setVariable(name, value); }
    public Object removeVariable(String name) { return state.removeVariable(name); }
    public Map<String, Object> variables() { return state.variables(); }
    public List<String> namespaces() { return state.namespaces(); }
    public ScriptContext withNamespaces(String... values) { return state.withNamespaces(values); }
    public Locale locale() { return state.locale(); }
    public <T> Optional<T> service(Class<T> type) { return state.service(type); }
    public <T> T requireService(Class<T> type) { return state.requireService(type); }

    public static final class Builder {
        private final ScriptContextState.BuilderState state = new ScriptContextState.BuilderState();
        private Builder() { }
        public Builder sender(Object value) { state.sender(value); return this; }
        public Builder senderVariable(String name) { state.senderVariable(name); return this; }
        public Builder variable(String name, Object value) { state.variable(name, value); return this; }
        public Builder variables(Map<String, ?> values) { state.variables(values); return this; }
        public Builder namespaces(String... values) { state.namespaces(values); return this; }
        public Builder locale(Locale value) { state.locale(value); return this; }
        public <T> Builder service(Class<T> type, T service) { state.service(type, service); return this; }
        public ScriptContext build() { return state.build(); }
    }
}
