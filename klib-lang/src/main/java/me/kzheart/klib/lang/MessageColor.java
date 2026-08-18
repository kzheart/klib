package me.kzheart.klib.lang;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** 带旧版具名常量并能在 1.12 上确定性降级的 RGB 颜色。 */
public final class MessageColor {
    public static final MessageColor BLACK = legacy("black", '0', 0x000000);
    public static final MessageColor DARK_BLUE = legacy("dark_blue", '1', 0x0000AA);
    public static final MessageColor DARK_GREEN = legacy("dark_green", '2', 0x00AA00);
    public static final MessageColor DARK_AQUA = legacy("dark_aqua", '3', 0x00AAAA);
    public static final MessageColor DARK_RED = legacy("dark_red", '4', 0xAA0000);
    public static final MessageColor DARK_PURPLE = legacy("dark_purple", '5', 0xAA00AA);
    public static final MessageColor GOLD = legacy("gold", '6', 0xFFAA00);
    public static final MessageColor GRAY = legacy("gray", '7', 0xAAAAAA);
    public static final MessageColor DARK_GRAY = legacy("dark_gray", '8', 0x555555);
    public static final MessageColor BLUE = legacy("blue", '9', 0x5555FF);
    public static final MessageColor GREEN = legacy("green", 'a', 0x55FF55);
    public static final MessageColor AQUA = legacy("aqua", 'b', 0x55FFFF);
    public static final MessageColor RED = legacy("red", 'c', 0xFF5555);
    public static final MessageColor LIGHT_PURPLE = legacy("light_purple", 'd', 0xFF55FF);
    public static final MessageColor YELLOW = legacy("yellow", 'e', 0xFFFF55);
    public static final MessageColor WHITE = legacy("white", 'f', 0xFFFFFF);

    private static final List<MessageColor> LEGACY = Collections.unmodifiableList(Arrays.asList(
            BLACK, DARK_BLUE, DARK_GREEN, DARK_AQUA, DARK_RED, DARK_PURPLE, GOLD, GRAY,
            DARK_GRAY, BLUE, GREEN, AQUA, RED, LIGHT_PURPLE, YELLOW, WHITE));

    private final String name;
    private final int rgb;
    private final Character legacyCode;

    private MessageColor(String name, int rgb, Character legacyCode) {
        this.name = name;
        this.rgb = rgb;
        this.legacyCode = legacyCode;
    }

    public static MessageColor rgb(int rgb) {
        if (rgb < 0 || rgb > 0xFFFFFF) {
            throw new IllegalArgumentException("rgb must be between 0x000000 and 0xFFFFFF");
        }
        return new MessageColor(String.format(Locale.ROOT, "#%06X", rgb), rgb, null);
    }

    public int rgb() {
        return rgb;
    }

    public boolean isLegacy() {
        return legacyCode != null;
    }

    public char legacyCode() {
        return nearestLegacy().legacyCode.charValue();
    }

    public MessageColor nearestLegacy() {
        MessageColor nearest = null;
        long distance = Long.MAX_VALUE;
        int red = rgb >> 16 & 0xFF;
        int green = rgb >> 8 & 0xFF;
        int blue = rgb & 0xFF;
        for (MessageColor color : LEGACY) {
            long dr = red - (color.rgb >> 16 & 0xFF);
            long dg = green - (color.rgb >> 8 & 0xFF);
            long db = blue - (color.rgb & 0xFF);
            long candidate = dr * dr + dg * dg + db * db;
            if (candidate < distance) {
                distance = candidate;
                nearest = color;
            }
        }
        return nearest;
    }

    static MessageColor fromLegacy(char code) {
        char normalized = Character.toLowerCase(code);
        for (MessageColor color : LEGACY) {
            if (color.legacyCode.charValue() == normalized) {
                return color;
            }
        }
        return null;
    }

    static MessageColor fromTag(String tag) {
        if (tag.matches("#[0-9a-fA-F]{6}")) {
            return rgb(Integer.parseInt(tag.substring(1), 16));
        }
        String normalized = tag.toLowerCase(Locale.ROOT).replace('-', '_');
        for (MessageColor color : LEGACY) {
            if (color.name.equals(normalized)) {
                return color;
            }
        }
        return null;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof MessageColor && rgb == ((MessageColor) other).rgb;
    }

    @Override
    public int hashCode() {
        return rgb;
    }

    @Override
    public String toString() {
        return name;
    }

    private static MessageColor legacy(String name, char code, int rgb) {
        return new MessageColor(name, rgb, Character.valueOf(code));
    }
}
