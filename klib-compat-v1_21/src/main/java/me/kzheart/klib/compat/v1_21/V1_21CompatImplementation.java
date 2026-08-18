package me.kzheart.klib.compat.v1_21;

import me.kzheart.klib.compat.AbstractCompatProvider;
import me.kzheart.klib.compat.Capabilities;
import me.kzheart.klib.compat.Capability;
import me.kzheart.klib.compat.InventoryBridge;
import me.kzheart.klib.compat.MaterialBridge;
import me.kzheart.klib.compat.NbtBridge;
import me.kzheart.klib.compat.ServerVersion;
import me.kzheart.klib.compat.TextBridge;

import java.util.HashMap;
import java.util.Map;

/** 使用数据组件物品模型的 Minecraft 1.21 适配器。 */
public final class V1_21CompatImplementation extends AbstractCompatProvider {
    public static final String ID = "compat-v1_21";
    public static final String SERVER_VERSION = "1.21";

    public V1_21CompatImplementation() {
        super(ID, SERVER_VERSION, bridges());
    }

    @Override
    public boolean supports(ServerVersion serverVersion) {
        // 覆盖 v26 基准版本 26.2 之前的所有版本（不含 26.2），
        // 使 26.0/26.1 服务器仍能解析到此适配器。
        return serverVersion.compareTo(version()) >= 0
                && serverVersion.compareTo(ServerVersion.of(26, 2, 0)) < 0;
    }

    private static Map<Capability<?>, Object> bridges() {
        Map<Capability<?>, Object> bridges = new HashMap<Capability<?>, Object>();
        bridges.put(Capabilities.TEXT, new V1_21TextBridge());
        bridges.put(Capabilities.NBT, new V1_21NbtBridge());
        bridges.put(Capabilities.MATERIAL, new V1_21MaterialBridge());
        bridges.put(Capabilities.INVENTORY, new V1_21InventoryBridge());
        return bridges;
    }

    private static final class V1_21TextBridge implements TextBridge {
        @Override public boolean supportsHexColors() { return true; }
    }

    private static final class V1_21NbtBridge implements NbtBridge {
        @Override public boolean supportsPersistentDataContainer() { return true; }
    }

    private static final class V1_21MaterialBridge implements MaterialBridge {
        @Override public boolean usesLegacyNames() { return false; }
    }

    private static final class V1_21InventoryBridge implements InventoryBridge {
        @Override public boolean supportsRuntimeTitleUpdates() { return true; }
    }
}
