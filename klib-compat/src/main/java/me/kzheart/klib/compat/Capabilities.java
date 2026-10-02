package me.kzheart.klib.compat;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** 版本适配器与调用方共享的稳定能力键。 */
public final class Capabilities {
    public static final Capability<TextBridge> TEXT = Capability.of("text", TextBridge.class);
    public static final Capability<NbtBridge> NBT = Capability.of("nbt", NbtBridge.class);
    public static final Capability<MaterialBridge> MATERIAL = Capability.of("material", MaterialBridge.class);
    public static final Capability<InventoryBridge> INVENTORY = Capability.of("inventory", InventoryBridge.class);
    /** 按玩家发送的数据包侧边栏；只有经过真实服务端验证的实现才会公开。 */
    public static final Capability<SidebarBridge> SIDEBAR = Capability.of("sidebar", SidebarBridge.class);
    private static final List<Capability<?>> VALUES = Collections.unmodifiableList(Arrays.<Capability<?>>asList(
            TEXT,
            NBT,
            MATERIAL,
            INVENTORY,
            SIDEBAR
    ));

    private Capabilities() {
    }

    /** 返回能力矩阵检查的全部能力；{@link #SIDEBAR} 之外的能力由每个内置实现完整公开。 */
    public static List<Capability<?>> values() {
        return VALUES;
    }
}
