package me.kzheart.klib.compat.v1_12;

import org.junit.jupiter.api.Test;
import org.bukkit.ChatColor;

import java.util.HashSet;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V1_12SidebarBridgeTest {
    @Test
    void ownersAreInvisibleUniqueAndFitLegacyLimits() {
        Set<String> owners = new HashSet<String>();
        for (int index = 0; index < 15; index++) {
            String owner = V1_12SidebarBridge.owner("ks0123abcd", index);
            assertTrue(owner.length() <= 40, owner);
            assertTrue(owner.matches("(§[0-9a-f])+§r"), owner);
            owners.add(owner);
            assertTrue(V1_12SidebarBridge.team("ks0123abcd", index).length() <= 16);
        }
        assertEquals(15, owners.size());
        assertTrue(!V1_12SidebarBridge.owner("ks0123abcd", 0).equals(V1_12SidebarBridge.owner("ks0123abce", 0)));
    }

    @Test
    void legacyTextSplitsIntoPrefixAndColoredSuffix() {
        assertArrayEquals(new String[]{"short", ""}, V1_12SidebarBridge.splitLegacy("short"));
        assertArrayEquals(new String[]{"§aThis is a long", "§a line"},
                V1_12SidebarBridge.splitLegacy("§aThis is a long line"));
        assertArrayEquals(new String[]{"§e1234567890123", "§e§c45"},
                V1_12SidebarBridge.splitLegacy("§e1234567890123§c45"));
        String[] longest = V1_12SidebarBridge.splitLegacy("0123456789abcdef0123456789abcdefXYZ");
        assertEquals("0123456789abcdef", longest[0]);
        assertEquals("0123456789abcdef", longest[1]);
    }

    @Test
    void truncationNeverLeavesADanglingColorChar() {
        assertEquals("abc", V1_12SidebarBridge.truncateLegacy("abc", 32));
        assertEquals("abc", V1_12SidebarBridge.truncateLegacy("abc§a", 4));
        assertEquals("abcd", V1_12SidebarBridge.truncateLegacy("abcdef", 4));
    }

    @Test
    void exactBoundariesRetainTextAndSplitColorCodesOnlyWhenNecessary() {
        assertArrayEquals(new String[]{"", ""}, V1_12SidebarBridge.splitLegacy(""));
        assertArrayEquals(new String[]{"0123456789abcdef", ""},
                V1_12SidebarBridge.splitLegacy("0123456789abcdef"));
        assertArrayEquals(new String[]{"0123456789abcde", "§aZ"},
                V1_12SidebarBridge.splitLegacy("0123456789abcde§aZ"));
        assertArrayEquals(new String[]{"0123456789abcdef", "§aZ"},
                V1_12SidebarBridge.splitLegacy("0123456789abcdef§aZ"));
        // 原规则只在截断时处理孤立颜色符，不清洗本来就在长度限制内的内容。
        assertArrayEquals(new String[]{"0123456789abcde§", ""},
                V1_12SidebarBridge.splitLegacy("0123456789abcde§"));
        assertEquals("abc§", V1_12SidebarBridge.truncateLegacy("abc§", 4));
        assertEquals("", V1_12SidebarBridge.truncateLegacy("", 0));
    }

    @Test
    void legacyLengthsContinueToCountUtf16CodeUnits() {
        String first = "0123456789abcde\uD83D";
        assertArrayEquals(new String[]{first, "\uDE00tail"},
                V1_12SidebarBridge.splitLegacy(first + "\uDE00tail"));
        assertEquals(first, V1_12SidebarBridge.truncateLegacy(first + "\uDE00tail", 16));
    }

    @Test
    void ownersPreserveJavaHashAndLowByteEncoding() {
        String[] objectives = {"", "ks0123abcd", "Aa", "BB", "中文", "\uD83D\uDE00", "negative-hash"};
        int[] indices = {Integer.MIN_VALUE, -257, -1, 0, 14, 15, 16, 255, 256, Integer.MAX_VALUE};
        for (String objective : objectives) {
            for (int index : indices) {
                String hex = String.valueOf(Character.forDigit(index >> 4 & 0xf, 16))
                        + Character.forDigit(index & 0xf, 16);
                String key = String.format(Locale.ROOT, "%08x", objective.hashCode()) + hex;
                StringBuilder expected = new StringBuilder();
                for (int i = 0; i < key.length(); i++) expected.append('§').append(key.charAt(i));
                expected.append("§r");
                assertEquals(expected.toString(), V1_12SidebarBridge.owner(objective, index));
                assertEquals(objective + hex, V1_12SidebarBridge.team(objective, index));
            }
        }
        assertEquals(15, new V1_12SidebarBridge().maxLines());
    }

    @Test
    void textOperationsMatchTheOriginalJavaRulesAcrossFormattingAndLengths() {
        Random random = new Random(0x11219L);
        String alphabet = "abc中文§0123456789ABCDEFabcdefklmnor\n\uD83D\uDE00";
        for (int sample = 0; sample < 800; sample++) {
            StringBuilder input = new StringBuilder();
            int length = sample % 81;
            for (int i = 0; i < length; i++) input.append(alphabet.charAt(random.nextInt(alphabet.length())));
            String text = input.toString();
            assertArrayEquals(originalSplit(text), V1_12SidebarBridge.splitLegacy(text), "sample " + sample);
            for (int limit : new int[]{1, 15, 16, 31, 32}) {
                assertEquals(originalTruncate(text, limit), V1_12SidebarBridge.truncateLegacy(text, limit),
                        "sample " + sample + ", limit " + limit);
            }
        }
    }

    // Kotlin 迁移前的 Java 算法作为等价性 oracle；不与生产实现共享拆分逻辑。
    private static String[] originalSplit(String text) {
        if (text.length() <= 16) return new String[]{text, ""};
        String prefix = text.substring(0, 16);
        String suffix;
        if (prefix.charAt(15) == ChatColor.COLOR_CHAR) {
            prefix = prefix.substring(0, 15);
            suffix = ChatColor.getLastColors(prefix) + text.substring(15);
        } else {
            suffix = ChatColor.getLastColors(prefix) + text.substring(16);
        }
        return new String[]{prefix, originalTruncate(suffix, 16)};
    }

    private static String originalTruncate(String text, int limit) {
        if (text.length() <= limit) return text;
        String cut = text.substring(0, limit);
        return cut.charAt(limit - 1) == ChatColor.COLOR_CHAR ? cut.substring(0, limit - 1) : cut;
    }
}
