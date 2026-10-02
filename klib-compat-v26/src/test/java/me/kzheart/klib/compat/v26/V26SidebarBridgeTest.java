package me.kzheart.klib.compat.v26;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V26SidebarBridgeTest {
    @Test
    void ownersAreInvisibleAndUniquePerObjectiveAndLine() {
        Set<String> owners = new HashSet<String>();
        for (int index = 0; index < new V26SidebarBridge().maxLines(); index++) {
            String owner = V26SidebarBridge.owner("ks0123abcd", index);
            assertTrue(owner.matches("(§.)+"), owner);
            owners.add(owner);
        }
        owners.add(V26SidebarBridge.owner("ks0123abce", 0));
        assertEquals(16, owners.size());
    }
}
