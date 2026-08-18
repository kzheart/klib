package me.kzheart.klib.compat.v1_20;

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

/** Minecraft 1.20.4 兼容实现的运行时描述。 */
public final class V1_20CompatImplementation extends AbstractCompatProvider {
    public static final String ID = "compat-v1_20";
    public static final String SERVER_VERSION = "1.20.4";

    public V1_20CompatImplementation() {
        super(ID, SERVER_VERSION, bridges());
    }

    @Override
    public boolean supports(ServerVersion serverVersion) {
        return serverVersion.compareTo(ServerVersion.of(1, 20, 0)) >= 0
                && serverVersion.compareTo(ServerVersion.of(1, 21, 0)) < 0;
    }

    private static Map<Capability<?>, Object> bridges() {
        Map<Capability<?>, Object> bridges = new HashMap<Capability<?>, Object>();
        bridges.put(Capabilities.TEXT, new TextBridge() {
            @Override public boolean supportsHexColors() { return true; }
        });
        bridges.put(Capabilities.NBT, new NbtBridge() {
            @Override public boolean supportsPersistentDataContainer() { return true; }
        });
        bridges.put(Capabilities.MATERIAL, new MaterialBridge() {
            @Override public boolean usesLegacyNames() { return false; }
        });
        bridges.put(Capabilities.INVENTORY, new InventoryBridge() {
            @Override public boolean supportsRuntimeTitleUpdates() { return true; }
        });
        return bridges;
    }
}
