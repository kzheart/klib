package me.kzheart.klib.hook.cost;

import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 消耗类型注册表，把 {@code money:500}、{@code item:mi:MATERIAL:SOUL_GEM*3} 这类配置条目解析为 {@link CostPlan}。
 *
 * <p>条目末尾可追加 {@code " | 描述"} 覆盖面向玩家的描述，例如 {@code perm:vip.use | 需要 VIP}。
 */
public final class Costs {
    private final Map<String, CostType> types;

    private Costs(Map<String, CostType> types) {
        this.types = Collections.unmodifiableMap(new LinkedHashMap<String, CostType>(types));
    }

    public static Builder builder() {
        return new Builder();
    }

    public CostPlan parse(String... entries) {
        return parse(Arrays.asList(entries));
    }

    /** 任一条目非法时抛出 {@link IllegalArgumentException}，消息包含条目序号与原文。 */
    public CostPlan parse(Collection<String> entries) {
        Objects.requireNonNull(entries, "entries");
        List<Cost> costs = new ArrayList<Cost>(entries.size());
        int index = 0;
        for (String raw : entries) {
            try {
                costs.add(parseOne(raw));
            } catch (IllegalArgumentException failure) {
                throw new IllegalArgumentException("Invalid cost #" + (index + 1) + " '" + raw + "': "
                        + failure.getMessage(), failure);
            }
            index++;
        }
        return new CostPlan(costs);
    }

    public Cost parseOne(String raw) {
        Entry entry = Entry.parse(raw);
        CostType type = types.get(entry.type);
        if (type == null) {
            throw new IllegalArgumentException("Unknown cost type '" + entry.type + "', known: " + types.keySet());
        }
        Cost cost = Objects.requireNonNull(type.parse(entry.argument), "parsed cost");
        return entry.description == null ? cost : new Described(cost, entry.description);
    }

    public Collection<String> types() {
        return types.keySet();
    }

    private static final class Described implements Cost {
        private final Cost delegate;
        private final String description;

        private Described(Cost delegate, String description) {
            this.delegate = delegate;
            this.description = description;
        }

        @Override
        public String describe() {
            return description;
        }

        @Override
        public boolean test(Player player) {
            return delegate.test(player);
        }

        @Override
        public Charge take(Player player) {
            return delegate.take(player);
        }
    }

    /** {@link Costs} 构建器。 */
    public static final class Builder {
        private final Map<String, CostType> types = new LinkedHashMap<String, CostType>();

        private Builder() {
        }

        public Builder type(String prefix, CostType type) {
            Objects.requireNonNull(type, "type");
            String key = Objects.requireNonNull(prefix, "prefix").trim().toLowerCase(java.util.Locale.ROOT);
            if (!key.matches("[a-z0-9_-]+")) {
                throw new IllegalArgumentException("Invalid cost type prefix: '" + prefix + "'");
            }
            if (types.putIfAbsent(key, type) != null) {
                throw new IllegalStateException("Cost type already registered: " + key);
            }
            return this;
        }

        /** 注册 {@code perm} 与 {@code level} 两个无需外部依赖的类型。 */
        public Builder defaults() {
            return type("perm", CostTypes.permission()).type("level", CostTypes.level());
        }

        public Costs build() {
            return new Costs(types);
        }
    }
}
