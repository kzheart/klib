package me.kzheart.klib.item;

import java.util.Optional;
import org.bukkit.inventory.ItemStack;

/** 对调用方隐藏存储细节的类型化物品标签，保留 Java 泛型平台返回类型。 */
public final class TagKey<T> {
    private final TagKeyValue<T> state;

    private TagKey(TagKeyValue<T> state) { this.state = state; }

    public static TagKey<String> string(String key) { return new TagKey<String>(TagKeyValue.string(key)); }
    public static TagKey<Integer> integer(String key) { return new TagKey<Integer>(TagKeyValue.integer(key)); }
    public static TagKey<Long> longValue(String key) { return new TagKey<Long>(TagKeyValue.longValue(key)); }
    public static TagKey<Double> doubleValue(String key) { return new TagKey<Double>(TagKeyValue.doubleValue(key)); }
    public static TagKey<Boolean> bool(String key) { return new TagKey<Boolean>(TagKeyValue.bool(key)); }
    public static TagKey<byte[]> bytes(String key) { return new TagKey<byte[]>(TagKeyValue.bytes(key)); }

    public TagKey<T> using(ItemTagBridge replacement) { return new TagKey<T>(state.using(replacement)); }
    public String value() { return state.value(); }
    public Class<T> type() { return state.type(); }
    public T get(ItemStack item) { return state.get(item, this); }
    public Optional<T> find(ItemStack item) { return state.find(item, this); }
    public T getOrDefault(ItemStack item, T fallback) { return state.getOrDefault(item, this, fallback); }
    public void set(ItemStack item, T value) { state.set(item, this, value); }
    public boolean has(ItemStack item) { return state.has(item, this); }
    public void remove(ItemStack item) { state.remove(item, this); }

    TagValueType<T> valueType() { return state.valueType(); }
    Object toStorage(T value) { return state.toStorage(value); }
    T fromStorage(Object value) { return state.fromStorage(value); }

    @Override public boolean equals(Object other) {
        return other instanceof TagKey<?> && state.equals(((TagKey<?>) other).state);
    }
    @Override public int hashCode() { return state.hashCode(); }
    @Override public String toString() { return state.toString(); }
}
