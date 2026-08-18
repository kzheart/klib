package me.kzheart.klib.compat;

import java.util.Objects;

/** 带类型且与实现无关的兼容能力键。 */
public final class Capability<T> {
    private final String id;
    private final Class<T> type;

    private Capability(String id, Class<T> type) {
        this.id = Objects.requireNonNull(id, "id");
        this.type = Objects.requireNonNull(type, "type");
    }

    public static <T> Capability<T> of(String id, Class<T> type) {
        return new Capability<T>(id, type);
    }

    public String id() {
        return id;
    }

    public Class<T> type() {
        return type;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Capability)) {
            return false;
        }
        Capability<?> that = (Capability<?>) other;
        return id.equals(that.id) && type.equals(that.type);
    }

    @Override
    public int hashCode() {
        return 31 * id.hashCode() + type.hashCode();
    }

    @Override
    public String toString() {
        return "Capability{" + id + ':' + type.getName() + '}';
    }
}
