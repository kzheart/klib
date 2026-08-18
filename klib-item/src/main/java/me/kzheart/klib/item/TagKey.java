package me.kzheart.klib.item;

import org.bukkit.inventory.ItemStack;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** 对调用方隐藏存储细节的类型化物品标签。 */
public final class TagKey<T> {
    private final String key;
    private final TagValueType<T> type;
    private final ItemTagBridge bridge;

    private TagKey(String key, TagValueType<T> type, ItemTagBridge bridge) {
        this.key = validate(key);
        this.type = Objects.requireNonNull(type, "type");
        this.bridge = Objects.requireNonNull(bridge, "bridge");
    }

    public static TagKey<String> string(String key) {
        return create(key, TagValueType.STRING);
    }

    public static TagKey<Integer> integer(String key) {
        return create(key, TagValueType.INTEGER);
    }

    public static TagKey<Long> longValue(String key) {
        return create(key, TagValueType.LONG);
    }

    public static TagKey<Double> doubleValue(String key) {
        return create(key, TagValueType.DOUBLE);
    }

    public static TagKey<Boolean> bool(String key) {
        return new TagKey<Boolean>(key, new BooleanTagValueType(), AdaptiveItemTagBridge.createDefault());
    }

    public static TagKey<byte[]> bytes(String key) {
        return create(key, TagValueType.BYTE_ARRAY);
    }

    private static <T> TagKey<T> create(String key, TagValueType<T> type) {
        return new TagKey<T>(key, type, AdaptiveItemTagBridge.createDefault());
    }

    public TagKey<T> using(ItemTagBridge replacement) {
        return new TagKey<T>(key, type, replacement);
    }

    public String value() {
        return key;
    }

    public Class<T> type() {
        return type.javaType();
    }

    /**
     * 读取标签值。物品上没有该标签时返回 {@code null}；
     * 需要显式的空值语义时使用 {@link #find(ItemStack)} 或 {@link #getOrDefault(ItemStack, Object)}。
     */
    public T get(ItemStack item) {
        return bridge.get(requireItem(item), this);
    }

    /** 读取标签值，缺失时返回空 {@link Optional}。 */
    public Optional<T> find(ItemStack item) {
        return Optional.ofNullable(get(item));
    }

    /** 读取标签值，缺失时返回给定默认值；默认值可以为 {@code null}。 */
    public T getOrDefault(ItemStack item, T fallback) {
        T value = get(item);
        return value == null ? fallback : value;
    }

    public void set(ItemStack item, T value) {
        Objects.requireNonNull(value, "value");
        bridge.set(requireItem(item), this, value);
    }

    public boolean has(ItemStack item) {
        return bridge.has(requireItem(item), this);
    }

    public void remove(ItemStack item) {
        bridge.remove(requireItem(item), this);
    }

    TagValueType<T> valueType() {
        return type;
    }

    Object toStorage(T value) {
        if (type instanceof BooleanTagValueType) {
            return Boolean.TRUE.equals(value) ? Byte.valueOf((byte) 1) : Byte.valueOf((byte) 0);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    T fromStorage(Object value) {
        if (value == null) {
            return null;
        }
        if (type instanceof BooleanTagValueType) {
            return (T) Boolean.valueOf(((Number) value).byteValue() != 0);
        }
        return type.javaType().cast(value);
    }

    private static String validate(String raw) {
        Objects.requireNonNull(raw, "key");
        String normalized = raw.toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9._-]+:[a-z0-9/._-]+")) {
            throw new IllegalArgumentException("Tag key must be namespace:path: " + raw);
        }
        return normalized;
    }

    private static ItemStack requireItem(ItemStack item) {
        return Objects.requireNonNull(item, "item");
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TagKey<?>)) {
            return false;
        }
        TagKey<?> that = (TagKey<?>) other;
        return key.equals(that.key) && type.javaType().equals(that.type.javaType());
    }

    @Override
    public int hashCode() {
        return 31 * key.hashCode() + type.javaType().hashCode();
    }

    @Override
    public String toString() {
        return "TagKey{" + key + ':' + type.javaType().getSimpleName() + '}';
    }

    private static final class BooleanTagValueType extends TagValueType<Boolean> {
        private BooleanTagValueType() {
            super(Boolean.class, "BYTE", "Byte");
        }
    }
}
