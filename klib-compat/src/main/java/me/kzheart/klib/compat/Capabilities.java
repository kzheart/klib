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
    private static final List<Capability<?>> VALUES = Collections.unmodifiableList(Arrays.<Capability<?>>asList(
            TEXT,
            NBT,
            MATERIAL,
            INVENTORY
    ));

    private Capabilities() {
    }

    /** 返回完整兼容提供者必须公开的全部能力。 */
    public static List<Capability<?>> values() {
        return VALUES;
    }
}
