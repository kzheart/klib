package me.kzheart.klib.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 将所有受支持的菜单声明编译为一个不可变模型。 */
public final class MenuCompiler {
    private MenuCompiler() {
    }

    public static MenuModel compile(MenuTemplate template) {
        Objects.requireNonNull(template, "template");
        Map<Integer, MenuEntry> entries = new LinkedHashMap<Integer, MenuEntry>();
        List<String> layout = template.layout();
        if (!layout.isEmpty() && layout.size() != template.rows()) {
            throw new IllegalArgumentException("layout line count must match menu rows");
        }
        for (int row = 0; row < layout.size(); row++) {
            String line = layout.get(row);
            if (line.length() != 9) {
                throw new IllegalArgumentException("layout rows must contain exactly 9 characters");
            }
            for (int column = 0; column < line.length(); column++) {
                char symbol = line.charAt(column);
                if (symbol == ' ') {
                    continue;
                }
                MenuEntry entry = template.characters().get(Character.valueOf(symbol));
                if (entry == null) {
                    throw new IllegalArgumentException("unbound layout character: " + symbol);
                }
                entries.put(Integer.valueOf(row * 9 + column), entry);
            }
        }
        for (Map.Entry<Integer, MenuEntry> slotEntry : template.slots().entrySet()) {
            if (entries.containsKey(slotEntry.getKey())) {
                throw new IllegalArgumentException(
                        "slot " + slotEntry.getKey() + " is already bound by the layout");
            }
            entries.put(slotEntry.getKey(), slotEntry.getValue());
        }
        return new MenuModel(template.title(), template.rows(), template.cancelClicks(), entries);
    }

    public static MenuModel compileSlots(
            String title,
            int rows,
            Map<Integer, MenuEntry> slots
    ) {
        MenuTemplate.Builder builder = MenuTemplate.builder(title, rows);
        for (Map.Entry<Integer, MenuEntry> entry : slots.entrySet()) {
            builder.slot(entry.getKey().intValue(), entry.getValue());
        }
        return compile(builder.build());
    }

    /**
     * 编译由 klib-config 解析为普通映射和列表后的 YAML 文档。
     * 支持的键包括 title、rows、cancel-clicks、layout、items 和 slots。
     */
    public static MenuModel compileYaml(Map<String, ?> yaml, YamlItemResolver resolver) {
        Objects.requireNonNull(yaml, "yaml");
        Objects.requireNonNull(resolver, "resolver");
        String title = string(yaml, "title", "Menu");
        int rows = integer(yaml, "rows", 1);
        MenuTemplate.Builder builder = MenuTemplate.builder(title, rows)
                .cancelClicks(bool(yaml, "cancel-clicks", true));

        Object rawLayout = yaml.get("layout");
        if (rawLayout != null) {
            if (!(rawLayout instanceof List<?>)) {
                throw new IllegalArgumentException("layout must be a list");
            }
            List<String> lines = new ArrayList<String>();
            for (Object line : (List<?>) rawLayout) {
                lines.add(String.valueOf(line));
            }
            builder.layout(lines.toArray(new String[lines.size()]));
        }
        Object rawItems = yaml.get("items");
        if (rawItems != null) {
            if (!(rawItems instanceof Map<?, ?>)) {
                throw new IllegalArgumentException("items must be a map");
            }
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) rawItems).entrySet()) {
                String symbol = String.valueOf(entry.getKey());
                if (symbol.length() != 1) {
                    throw new IllegalArgumentException("item character must have length 1: " + symbol);
                }
                builder.character(symbol.charAt(0), requireResolved(resolver, entry.getValue()));
            }
        }
        Object rawSlots = yaml.get("slots");
        if (rawSlots != null) {
            if (!(rawSlots instanceof Map<?, ?>)) {
                throw new IllegalArgumentException("slots must be a map");
            }
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) rawSlots).entrySet()) {
                int slot = Integer.parseInt(String.valueOf(entry.getKey()));
                builder.slot(slot, requireResolved(resolver, entry.getValue()));
            }
        }
        return compile(builder.build());
    }

    private static MenuEntry requireResolved(YamlItemResolver resolver, Object id) {
        return Objects.requireNonNull(resolver.resolve(String.valueOf(id)),
                "resolver returned null for " + id);
    }

    private static String string(Map<String, ?> map, String key, String fallback) {
        Object value = map.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private static int integer(Map<String, ?> map, String key, int fallback) {
        Object value = map.get(key);
        return value == null ? fallback : Integer.parseInt(String.valueOf(value));
    }

    private static boolean bool(Map<String, ?> map, String key, boolean fallback) {
        Object value = map.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean) {
            return ((Boolean) value).booleanValue();
        }
        String text = String.valueOf(value).trim();
        if (text.equalsIgnoreCase("true")) {
            return true;
        }
        if (text.equalsIgnoreCase("false")) {
            return false;
        }
        throw new IllegalArgumentException(
                key + " must be true or false, got: " + value);
    }
}
