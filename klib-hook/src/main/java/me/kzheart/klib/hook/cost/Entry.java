package me.kzheart.klib.hook.cost;

import java.util.Locale;
import java.util.Objects;

/** 配置条目 {@code type:argument}，可用 {@code " | 描述"} 覆盖面向玩家的描述。 */
final class Entry {
    private static final String DESCRIPTION_SEPARATOR = " | ";

    final String raw;
    final String type;
    final String argument;
    final String description;

    private Entry(String raw, String type, String argument, String description) {
        this.raw = raw;
        this.type = type;
        this.argument = argument;
        this.description = description;
    }

    static Entry parse(String raw) {
        Objects.requireNonNull(raw, "entry");
        String body = raw.trim();
        String description = null;
        int split = body.lastIndexOf(DESCRIPTION_SEPARATOR);
        if (split >= 0) {
            description = body.substring(split + DESCRIPTION_SEPARATOR.length()).trim();
            body = body.substring(0, split).trim();
            if (description.isEmpty()) {
                throw new IllegalArgumentException("Empty description in '" + raw + "'");
            }
        }
        int colon = body.indexOf(':');
        if (colon <= 0) {
            throw new IllegalArgumentException("Entry must look like type:argument, got '" + raw + "'");
        }
        String argument = body.substring(colon + 1).trim();
        if (argument.isEmpty()) {
            throw new IllegalArgumentException("Missing argument in '" + raw + "'");
        }
        return new Entry(raw, body.substring(0, colon).trim().toLowerCase(Locale.ROOT), argument, description);
    }

    /** 解析 {@code ref*3} 形式的数量后缀，没有后缀时数量为 1。 */
    static int amountOf(String argument) {
        int star = argument.lastIndexOf('*');
        if (star < 0) {
            return 1;
        }
        String amount = argument.substring(star + 1).trim();
        try {
            int value = Integer.parseInt(amount);
            if (value < 1) {
                throw new IllegalArgumentException("Amount must be positive: " + argument);
            }
            return value;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Invalid amount in '" + argument + "'", failure);
        }
    }

    static String withoutAmount(String argument) {
        int star = argument.lastIndexOf('*');
        String reference = (star < 0 ? argument : argument.substring(0, star)).trim();
        if (reference.isEmpty()) {
            throw new IllegalArgumentException("Missing item reference in '" + argument + "'");
        }
        return reference;
    }
}
