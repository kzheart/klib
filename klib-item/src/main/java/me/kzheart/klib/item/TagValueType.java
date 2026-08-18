package me.kzheart.klib.item;

import java.util.Arrays;

class TagValueType<T> {
    static final TagValueType<String> STRING = new TagValueType<String>(String.class, "STRING", "String");
    static final TagValueType<Integer> INTEGER = new TagValueType<Integer>(Integer.class, "INTEGER", "Integer");
    static final TagValueType<Long> LONG = new TagValueType<Long>(Long.class, "LONG", "Long");
    static final TagValueType<Double> DOUBLE = new TagValueType<Double>(Double.class, "DOUBLE", "Double");
    static final TagValueType<Byte> BYTE = new TagValueType<Byte>(Byte.class, "BYTE", "Byte");
    static final TagValueType<byte[]> BYTE_ARRAY = new TagValueType<byte[]>(byte[].class, "BYTE_ARRAY", "ByteArray");

    private final Class<T> javaType;
    private final String pdcField;
    private final String legacySuffix;

    TagValueType(Class<T> javaType, String pdcField, String legacySuffix) {
        this.javaType = javaType;
        this.pdcField = pdcField;
        this.legacySuffix = legacySuffix;
    }

    Class<T> javaType() {
        return javaType;
    }

    String pdcField() {
        return pdcField;
    }

    String legacyGetter() {
        return "get" + legacySuffix;
    }

    String legacySetter() {
        return "set" + legacySuffix;
    }

    boolean valuesEqual(Object left, Object right) {
        if (left instanceof byte[] && right instanceof byte[]) {
            return Arrays.equals((byte[]) left, (byte[]) right);
        }
        return left == null ? right == null : left.equals(right);
    }
}
