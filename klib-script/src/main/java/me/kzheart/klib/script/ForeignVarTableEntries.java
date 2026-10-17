package me.kzheart.klib.script;

import java.util.Collection;
import java.util.Map;
import java.util.LinkedHashMap;
import me.kzheart.klib.script.kether.core.QuestContext;

/**
 * Preserves the Java Map.Entry generic boundary for Kotlin mixed-source compilation.
 * The remote collection is passed through unchanged; all lookup and lifecycle behavior is Kotlin.
 */
abstract class ForeignVarTableEntries implements QuestContext.VarTable {
    protected abstract Object rawEntries();

    static Map<String, Object> snapshot(QuestContext.VarTable table) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : table.values()) {
            result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }

    @Override
    @SuppressWarnings("unchecked")
    public final Collection<Map.Entry<String, Object>> values() {
        return (Collection<Map.Entry<String, Object>>) rawEntries();
    }
}
