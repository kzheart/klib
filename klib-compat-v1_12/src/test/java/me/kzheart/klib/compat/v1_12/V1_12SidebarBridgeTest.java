package me.kzheart.klib.compat.v1_12;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
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
}
