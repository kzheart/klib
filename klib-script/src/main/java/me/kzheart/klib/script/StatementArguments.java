package me.kzheart.klib.script;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** {@link Statements} 组合解析器生成的具名参数。 */
public final class StatementArguments {

    private final Map<String, String> values;

    StatementArguments(Map<String, String> values) {
        this.values = Collections.unmodifiableMap(new LinkedHashMap<String, String>(values));
    }

    public String require(String name) {
        String value = values.get(Objects.requireNonNull(name, "name"));
        if (value == null) {
            throw new IllegalArgumentException("Missing combined argument: " + name);
        }
        return value;
    }

    public Optional<String> get(String name) {
        return Optional.ofNullable(values.get(Objects.requireNonNull(name, "name")));
    }

    public Object value(String name, ScriptContext context) {
        String value = require(name);
        return InlineValues.value(value, context);
    }

    public Map<String, String> asMap() {
        return values;
    }
}
