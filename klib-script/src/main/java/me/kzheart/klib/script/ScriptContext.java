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

/** 单次求值可见的发送者、变量、命名空间与宿主服务。 */
public final class ScriptContext {

    private final Object sender;
    private final ConcurrentMap<String, Object> variables;
    private final List<String> namespaces;
    private final Locale locale;
    private final Map<Class<?>, Object> services;

    private ScriptContext(Builder builder) {
        sender = builder.sender;
        variables = new ConcurrentHashMap<String, Object>(builder.variables);
        namespaces = Collections.unmodifiableList(new ArrayList<String>(builder.namespaces));
        locale = builder.locale;
        services = Collections.unmodifiableMap(new LinkedHashMap<Class<?>, Object>(builder.services));
    }

    private ScriptContext(ScriptContext source, List<String> selectedNamespaces) {
        sender = source.sender;
        variables = source.variables;
        namespaces = Collections.unmodifiableList(new ArrayList<String>(selectedNamespaces));
        locale = source.locale;
        services = source.services;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Optional<Object> sender() {
        return Optional.ofNullable(sender);
    }

    public Optional<Object> variable(String name) {
        return Optional.ofNullable(variables.get(requireName(name)));
    }

    public Object variableOrNull(String name) {
        return variables.get(requireName(name));
    }

    public void setVariable(String name, Object value) {
        String normalized = requireName(name);
        if (value == null) {
            variables.remove(normalized);
        } else {
            variables.put(normalized, value);
        }
    }

    public Object removeVariable(String name) {
        return variables.remove(requireName(name));
    }

    public Map<String, Object> variables() {
        return Collections.unmodifiableMap(new LinkedHashMap<String, Object>(variables));
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

        public Builder variable(String name, Object value) {
            variables.put(requireName(name), Objects.requireNonNull(value, "value"));
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
