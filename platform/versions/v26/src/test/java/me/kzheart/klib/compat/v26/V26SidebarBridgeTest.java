package me.kzheart.klib.compat.v26;

import org.junit.jupiter.api.Test;
import me.kzheart.klib.compat.internal.ModernSidebarBridge;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V26SidebarBridgeTest {
    @Test
    void usesSharedModernPacketShape() {
        assertTrue(new V26SidebarBridge() instanceof ModernSidebarBridge);
        assertEquals(15, new V26SidebarBridge().maxLines());
    }
}
