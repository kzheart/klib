package me.kzheart.klib.item;

import java.util.Objects;

/** 形如 {@code prefix:id} 的外部物品引用；原版材质的前缀为 {@code minecraft}。 */
public final class ItemRef {
    public static final String VANILLA = "minecraft";

    private final String prefix;
    private final String id;

    private ItemRef(String prefix, String id) {
        this.prefix = prefix;
        this.id = id;
    }

    public static ItemRef of(String prefix, String id) {
        return new ItemRef(requirePrefix(prefix), requireId(id));
    }

    public String prefix() {
        return prefix;
    }

    public String id() {
        return id;
    }

    public boolean vanilla() {
        return VANILLA.equals(prefix);
    }

    static String requirePrefix(String prefix) {
        Objects.requireNonNull(prefix, "prefix");
        if (!prefix.matches("[a-z0-9_-]+")) {
            throw new IllegalArgumentException("Invalid item source prefix: '" + prefix + "'");
        }
        return prefix;
    }

    private static String requireId(String id) {
        Objects.requireNonNull(id, "id");
        if (id.trim().isEmpty()) {
            throw new IllegalArgumentException("Item id must not be blank");
        }
        return id;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ItemRef)) {
            return false;
        }
        ItemRef that = (ItemRef) other;
        return prefix.equals(that.prefix) && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return 31 * prefix.hashCode() + id.hashCode();
    }

    @Override
    public String toString() {
        return prefix + ':' + id;
    }
}
