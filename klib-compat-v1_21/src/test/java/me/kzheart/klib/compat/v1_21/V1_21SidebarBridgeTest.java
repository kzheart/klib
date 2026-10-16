package me.kzheart.klib.compat.v1_21;

import org.junit.jupiter.api.Test;
import me.kzheart.klib.compat.internal.ModernSidebarBridge;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V1_21SidebarBridgeTest {
    @Test
    void usesSharedModernPacketShape() {
        assertTrue(new V1_21SidebarBridge() instanceof ModernSidebarBridge);
        assertEquals(15, new V1_21SidebarBridge().maxLines());
    }
}
