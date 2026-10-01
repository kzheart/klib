package me.kzheart.klib.script;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import me.kzheart.klib.script.kether.core.QuestContext;

/** 单次求值可见的发送者、变量、命名空间与宿主服务。 */
public final class ScriptContext {

    private static final Object NULL_VALUE = new Object();

    private final Object sender;
    private final String senderVariable;
    private final ConcurrentMap<String, Object> variables;
    private final QuestContext.Frame frame;
    private final List<String> namespaces;
    private final Locale locale;
    private final Map<Class<?>, Object> services;

    private ScriptContext(Builder builder) {
        sender = builder.sender;
        senderVariable = builder.senderVariable;
        variables = new ConcurrentHashMap<String, Object>();
        for (Map.Entry<String, Object> entry : builder.variables.entrySet()) {
            variables.put(entry.getKey(), entry.getValue() == null ? NULL_VALUE : entry.getValue());
        }
        frame = null;
        namespaces = Collections.unmodifiableList(new ArrayList<String>(builder.namespaces));
        locale = builder.locale;
        services = Collections.unmodifiableMap(new LinkedHashMap<Class<?>, Object>(builder.services));
    }

    private ScriptContext(ScriptContext source, List<String> selectedNamespaces) {
        sender = source.sender;
        senderVariable = source.senderVariable;
        variables = source.variables;
        frame = source.frame;
        namespaces = Collections.unmodifiableList(new ArrayList<String>(selectedNamespaces));
        locale = source.locale;
        services = source.services;
    }

    private ScriptContext(ScriptContext source, QuestContext.Frame selectedFrame) {
        sender = source.sender;
        senderVariable = source.senderVariable;
        variables = source.variables;
        frame = selectedFrame;
        namespaces = source.namespaces;
        locale = source.locale;
        services = source.services;
    }

    ScriptContext atFrame(QuestContext.Frame selectedFrame) {
        return new ScriptContext(this, selectedFrame);
    }

    public static Builder builder() {
        return new Builder();
    }

    public Optional<Object> sender() {
        return Optional.ofNullable(senderVariable == null ? sender : variableOrNull(senderVariable));
    }

    public Optional<Object> variable(String name) {
        return Optional.ofNullable(variableOrNull(name));
    }

    public Object variableOrNull(String name) {
        String key = requireName(name);
        if (frame != null) {
            return ScriptFrames.isInternal(key) ? null : frame.variables().getOrNull(key);
        }
        Object value = variables.get(key);
        return value == NULL_VALUE ? null : value;
    }

    public void setVariable(String name, Object value) {
        String normalized = requireName(name);
        if (value == null) {
            removeVariable(normalized);
        } else {
            writeVariable(normalized, value);
        }
    }

    public Object removeVariable(String name) {
        String key = requireName(name);
        if (frame != null) {
            ScriptFrames.checkWritable(key);
            for (QuestContext.VarTable table = frame.variables(); table != null; table = table.parent()) {
                if (table.keys().contains(key)) {
                    Object previous = table.toMap().get(key);
                    table.remove(key);
                    return previous;
                }
            }
            return null;
        }
        Object previous = variables.remove(key);
        return previous == NULL_VALUE ? null : previous;
    }

    public Map<String, Object> variables() {
        if (frame != null) return ScriptFrames.variables(frame);
        Map<String, Object> snapshot = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> entry : variables.entrySet()) {
            snapshot.put(entry.getKey(), entry.getValue() == NULL_VALUE ? null : entry.getValue());
        }
        return Collections.unmodifiableMap(snapshot);
    }

    /** 引擎写回原生变量时保留显式 null；公开 setVariable(null) 仍表示移除。 */
    void writeVariable(String name, Object value) {
        if (frame == null) variables.put(name, value == null ? NULL_VALUE : value);
        else {
            ScriptFrames.checkWritable(name);
            frame.variables().set(name, value);
        }
    }

    public List<String> namespaces() {
        return namespaces;
    }

    public ScriptContext withNamespaces(String... values) {
        Objects.requireNonNull(values, "values");
        List<String> selected = new ArrayList<String>();
        for (String value : values) {
            selected.add(requireName(value));
        }
        for (String namespace : namespaces) {
            if (!selected.contains(namespace)) {
                selected.add(namespace);
            }
        }
        return new ScriptContext(this, selected);
    }

    public Locale locale() {
        return locale;
    }

    public <T> Optional<T> service(Class<T> type) {
        Objects.requireNonNull(type, "type");
        Object value = services.get(type);
        return value == null ? Optional.<T>empty() : Optional.of(type.cast(value));
    }

    public <T> T requireService(Class<T> type) {
        return service(type).orElseThrow(() -> new IllegalStateException(
                "Script service is not installed: " + type.getName()));
    }

    private static String requireName(String name) {
        Objects.requireNonNull(name, "name");
        String normalized = name.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Variable name must not be blank");
        }
        return normalized;
    }

    /** 构建隔离的上下文。 */
    public static final class Builder {

        private Object sender;
        private String senderVariable;
        private final Map<String, Object> variables = new LinkedHashMap<String, Object>();
        private final List<String> namespaces = new ArrayList<String>(
                Arrays.asList("klib", "global"));
        private Locale locale = Locale.SIMPLIFIED_CHINESE;
        private final Map<Class<?>, Object> services = new LinkedHashMap<Class<?>, Object>();

        private Builder() {
        }

        public Builder sender(Object value) {
            sender = value;
            return this;
        }

        /**
         * 每次读取发送者时使用当前帧可见变量；变量缺失或为 null 时没有发送者。
         * 不会写入该变量，也不会回退到 sender(Object)；默认仍使用固定发送者。
         */
        public Builder senderVariable(String name) {
            senderVariable = requireName(name);
            return this;
        }

        public Builder variable(String name, Object value) {
            variables.put(requireName(name), value);
            return this;
        }

        public Builder variables(Map<String, ?> values) {
            Objects.requireNonNull(values, "values");
            for (Map.Entry<String, ?> entry : values.entrySet()) {
                variable(entry.getKey(), entry.getValue());
            }
            return this;
        }

        public Builder namespaces(String... values) {
            Objects.requireNonNull(values, "values");
            namespaces.clear();
            for (String value : values) {
                namespaces.add(requireName(value));
            }
            if (!namespaces.contains("klib")) {
                namespaces.add("klib");
            }
            if (!namespaces.contains("global")) {
                namespaces.add("global");
            }
            return this;
        }

        public Builder locale(Locale value) {
            locale = Objects.requireNonNull(value, "value");
            return this;
        }

        public <T> Builder service(Class<T> type, T service) {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(service, "service");
            if (!type.isInstance(service)) {
                throw new IllegalArgumentException("Service does not implement " + type.getName());
            }
            services.put(type, service);
            return this;
        }

        public ScriptContext build() {
            return new ScriptContext(this);
        }
    }
}
