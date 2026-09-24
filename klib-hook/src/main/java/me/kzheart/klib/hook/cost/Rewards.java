package me.kzheart.klib.hook.cost;

import org.bukkit.Server;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 奖励类型注册表，条目格式与 {@link Costs} 相同。 */
public final class Rewards {
    private final Map<String, RewardType> types;

    private Rewards(Map<String, RewardType> types) {
        this.types = Collections.unmodifiableMap(new LinkedHashMap<String, RewardType>(types));
    }

    public static Builder builder() {
        return new Builder();
    }

    public RewardPlan parse(String... entries) {
        return parse(Arrays.asList(entries));
    }

    public RewardPlan parse(Collection<String> entries) {
        Objects.requireNonNull(entries, "entries");
        List<Reward> rewards = new ArrayList<Reward>(entries.size());
        int index = 0;
        for (String raw : entries) {
            try {
                rewards.add(parseOne(raw));
            } catch (IllegalArgumentException failure) {
                throw new IllegalArgumentException("Invalid reward #" + (index + 1) + " '" + raw + "': "
                        + failure.getMessage(), failure);
            }
            index++;
        }
        return new RewardPlan(rewards);
    }

    public Reward parseOne(String raw) {
        Entry entry = Entry.parse(raw);
        RewardType type = types.get(entry.type);
        if (type == null) {
            throw new IllegalArgumentException("Unknown reward type '" + entry.type + "', known: " + types.keySet());
        }
        Reward reward = Objects.requireNonNull(type.parse(entry.argument), "parsed reward");
        if (entry.description == null) {
            return reward;
        }
        String description = entry.description;
        return new Reward() {
            @Override
            public String describe() {
                return description;
            }

            @Override
            public void grant(Player player) {
                reward.grant(player);
            }
        };
    }

    public Collection<String> types() {
        return types.keySet();
    }

    /** {@link Rewards} 构建器。 */
    public static final class Builder {
        private final Map<String, RewardType> types = new LinkedHashMap<String, RewardType>();

        private Builder() {
        }

        public Builder type(String prefix, RewardType type) {
            Objects.requireNonNull(type, "type");
            String key = Objects.requireNonNull(prefix, "prefix").trim().toLowerCase(java.util.Locale.ROOT);
            if (!key.matches("[a-z0-9_-]+")) {
                throw new IllegalArgumentException("Invalid reward type prefix: '" + prefix + "'");
            }
            if (types.putIfAbsent(key, type) != null) {
                throw new IllegalStateException("Reward type already registered: " + key);
            }
            return this;
        }

        /** 注册 {@code console}、{@code player}、{@code op} 与 {@code msg}。 */
        public Builder defaults(Server server) {
            return type("console", RewardTypes.consoleCommand(server))
                    .type("player", RewardTypes.playerCommand())
                    .type("op", RewardTypes.opCommand())
                    .type("msg", RewardTypes.message());
        }

        public Rewards build() {
            return new Rewards(types);
        }
    }
}
