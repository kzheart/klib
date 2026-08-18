package me.kzheart.klib.item;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** 配置和钩子模块共用、仅解析一次的物品匹配器与生成器。 */
public final class ItemSpec {
    private final Material material;
    /** null 表示“不覆盖数量”（外部物品保留自身数量）。 */
    private final Integer amount;
    private final String generatedName;
    private final Pattern namePattern;
    private final List<Pattern> lorePatterns;
    private final Map<TagKey<?>, Object> tags;
    private final Predicate<ItemStack> predicate;
    private final String externalProvider;
    private final String externalId;
    private final ExternalItemProvider externals;

    private ItemSpec(Builder builder) {
        material = builder.material;
        amount = builder.amount;
        generatedName = builder.generatedName;
        namePattern = builder.namePattern;
        lorePatterns = Collections.unmodifiableList(new ArrayList<Pattern>(builder.lorePatterns));
        tags = Collections.unmodifiableMap(new LinkedHashMap<TagKey<?>, Object>(builder.tags));
        predicate = builder.predicate;
        externalProvider = builder.externalProvider;
        externalId = builder.externalId;
        externals = builder.externals;
        if (material == null && externalId == null) {
            throw new IllegalStateException("ItemSpec needs a material or external item id");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static ItemSpec from(Map<String, ?> values) {
        Objects.requireNonNull(values, "values");
        Builder builder = builder();
        Object material = values.get("material");
        if (material != null) {
            builder.material(material.toString());
        }
        Object amount = values.get("amount");
        if (amount instanceof Number) {
            builder.amount(((Number) amount).intValue());
        } else if (amount != null) {
            throw new IllegalArgumentException("amount must be a number, got: " + amount);
        }
        Object name = values.get("name");
        if (name != null) {
            builder.name(name.toString());
        }
        Object nameRegex = values.get("name-regex");
        if (nameRegex != null) {
            builder.nameRegex(nameRegex.toString());
        }
        Object lore = values.get("lore-regex");
        if (lore instanceof Iterable<?>) {
            for (Object line : (Iterable<?>) lore) {
                builder.loreRegex(String.valueOf(line));
            }
        }
        Object rawTags = values.get("tags");
        if (rawTags instanceof Map<?, ?>) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) rawTags).entrySet()) {
                builder.tag(TagKey.string(String.valueOf(entry.getKey())), String.valueOf(entry.getValue()));
            }
        }
        return builder.build();
    }

    public boolean matches(ItemStack item) {
        if (InventoryItems.isAir(item)) {
            return false;
        }
        if (material != null && item.getType() != material) {
            return false;
        }
        if (externalId != null && !externals.matches(externalProvider, externalId, item)) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (namePattern != null
                && (meta == null || !meta.hasDisplayName()
                || !namePattern.matcher(meta.getDisplayName()).matches())) {
            return false;
        }
        List<String> lore = meta != null && meta.hasLore() ? meta.getLore() : Collections.<String>emptyList();
        for (Pattern pattern : lorePatterns) {
            boolean found = false;
            for (String line : lore) {
                if (pattern.matcher(line).matches()) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        for (Map.Entry<TagKey<?>, Object> entry : tags.entrySet()) {
            if (!tagMatches(item, entry.getKey(), entry.getValue())) {
                return false;
            }
        }
        return predicate.test(item);
    }

    public ItemStack create() {
        ItemStack item = externalId == null
                ? new ItemStack(material, amount == null ? 1 : amount.intValue())
                : Objects.requireNonNull(externals.create(externalProvider, externalId), "external item").clone();
        if (amount != null) {
            item.setAmount(amount.intValue());
        }
        ItemBuilder builder = Items.edit(item);
        if (generatedName != null) {
            builder.name(generatedName);
        }
        ItemStack result = builder.build();
        for (Map.Entry<TagKey<?>, Object> entry : tags.entrySet()) {
            setTag(result, entry.getKey(), entry.getValue());
        }
        return result;
    }

    private static <T> boolean tagMatches(ItemStack item, TagKey<T> key, Object expected) {
        T actual = key.get(item);
        return key.valueType().valuesEqual(actual, expected);
    }

    @SuppressWarnings("unchecked")
    private static <T> void setTag(ItemStack item, TagKey<T> key, Object value) {
        key.set(item, (T) value);
    }

    public static final class Builder {
        private Material material;
        private Integer amount;
        private String generatedName;
        private Pattern namePattern;
        private final List<Pattern> lorePatterns = new ArrayList<Pattern>();
        private final Map<TagKey<?>, Object> tags = new LinkedHashMap<TagKey<?>, Object>();
        private Predicate<ItemStack> predicate = item -> true;
        private String externalProvider;
        private String externalId;
        private ExternalItemProvider externals;

        public Builder material(Material value) {
            material = Objects.requireNonNull(value, "material");
            return this;
        }

        public Builder material(String value) {
            return material(Items.resolveMaterial(value));
        }

        public Builder amount(int value) {
            if (value < 1 || value > 64) {
                throw new IllegalArgumentException("Amount must be between 1 and 64");
            }
            amount = Integer.valueOf(value);
            return this;
        }

        public Builder name(String value) {
            generatedName = Objects.requireNonNull(value, "name");
            return this;
        }

        public Builder nameRegex(String expression) {
            namePattern = Pattern.compile(Objects.requireNonNull(expression, "expression"));
            return this;
        }

        public Builder loreRegex(String expression) {
            lorePatterns.add(Pattern.compile(Objects.requireNonNull(expression, "expression")));
            return this;
        }

        public <T> Builder tag(TagKey<T> key, T value) {
            tags.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(value, "value"));
            return this;
        }

        public Builder matching(Predicate<ItemStack> value) {
            Objects.requireNonNull(value, "predicate");
            predicate = predicate.and(value);
            return this;
        }

        public Builder external(String provider, String id, ExternalItemProvider adapter) {
            externalProvider = Objects.requireNonNull(provider, "provider");
            externalId = Objects.requireNonNull(id, "id");
            externals = Objects.requireNonNull(adapter, "adapter");
            return this;
        }

        public ItemSpec build() {
            return new ItemSpec(this);
        }
    }
}
