package me.kzheart.klib.compat.internal;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModernSidebarBridgeTest {
    @Test
    void ownersAreInvisibleAndUniquePerObjectiveAndLine() {
        Set<String> owners = new HashSet<String>();
        for (int index = 0; index < new ModernSidebarBridge().maxLines(); index++) {
            String owner = ModernSidebarBridge.owner("ks0123abcd", index);
            assertTrue(owner.matches("(§.)+"), owner);
            owners.add(owner);
        }
        owners.add(ModernSidebarBridge.owner("ks0123abce", 0));
        assertEquals(16, owners.size());
    }
}
