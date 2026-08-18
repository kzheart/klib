package me.kzheart.klib.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** {@link MenuCompiler} 接受的源模板。 */
public final class MenuTemplate {
    private final String title;
    private final int rows;
    private final boolean cancelClicks;
    private final List<String> layout;
    private final Map<Character, MenuEntry> characters;
    private final Map<Integer, MenuEntry> slots;

    private MenuTemplate(Builder builder) {
        title = builder.title;
        rows = builder.rows;
        cancelClicks = builder.cancelClicks;
        layout = Collections.unmodifiableList(new ArrayList<String>(builder.layout));
        characters = Collections.unmodifiableMap(
                new LinkedHashMap<Character, MenuEntry>(builder.characters));
        slots = Collections.unmodifiableMap(new LinkedHashMap<Integer, MenuEntry>(builder.slots));
    }

    public static Builder builder(String title, int rows) {
        return new Builder(title, rows);
    }

    String title() {
        return title;
    }

    int rows() {
        return rows;
    }

    boolean cancelClicks() {
        return cancelClicks;
    }

    List<String> layout() {
        return layout;
    }

    Map<Character, MenuEntry> characters() {
        return characters;
    }

    Map<Integer, MenuEntry> slots() {
        return slots;
    }

    public static final class Builder {
        private final String title;
        private final int rows;
        private boolean cancelClicks = true;
        private final List<String> layout = new ArrayList<String>();
        private final Map<Character, MenuEntry> characters =
                new LinkedHashMap<Character, MenuEntry>();
        private final Map<Integer, MenuEntry> slots = new LinkedHashMap<Integer, MenuEntry>();

        private Builder(String title, int rows) {
            this.title = Objects.requireNonNull(title, "title");
            if (rows < 1 || rows > 6) {
                throw new IllegalArgumentException("rows must be between 1 and 6");
            }
            this.rows = rows;
        }

        public Builder cancelClicks(boolean value) {
            cancelClicks = value;
            return this;
        }

        public Builder layout(String... lines) {
            layout.clear();
            for (String line : lines) {
                layout.add(Objects.requireNonNull(line, "layout line"));
            }
            return this;
        }

        public Builder character(char symbol, MenuEntry entry) {
            if (symbol == ' ') {
                throw new IllegalArgumentException("space is reserved for an empty slot");
            }
            if (characters.put(Character.valueOf(symbol),
                    Objects.requireNonNull(entry, "entry")) != null) {
                throw new IllegalArgumentException("duplicate layout character: " + symbol);
            }
            return this;
        }

        public Builder slot(int slot, MenuEntry entry) {
            if (slots.put(Integer.valueOf(slot),
                    Objects.requireNonNull(entry, "entry")) != null) {
                throw new IllegalArgumentException("duplicate slot: " + slot);
            }
            return this;
        }

        public MenuTemplate build() {
            return new MenuTemplate(this);
        }
    }
}
