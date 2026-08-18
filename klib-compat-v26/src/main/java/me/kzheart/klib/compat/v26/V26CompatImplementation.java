package me.kzheart.klib.compat.v26;

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

/** 面向年份版本线的 Minecraft 26.x 适配器。 */
public final class V26CompatImplementation extends AbstractCompatProvider {
    public static final String ID = "compat-v26";
    public static final String SERVER_VERSION = "26.2";

    public V26CompatImplementation() {
        super(ID, SERVER_VERSION, bridges());
    }

    @Override
    public boolean supports(ServerVersion serverVersion) {
        return serverVersion.major() == version().major()
                && serverVersion.compareTo(version()) >= 0;
    }

    private static Map<Capability<?>, Object> bridges() {
        Map<Capability<?>, Object> bridges = new HashMap<Capability<?>, Object>();
        bridges.put(Capabilities.TEXT, new V26TextBridge());
        bridges.put(Capabilities.NBT, new V26NbtBridge());
        bridges.put(Capabilities.MATERIAL, new V26MaterialBridge());
        bridges.put(Capabilities.INVENTORY, new V26InventoryBridge());
        return bridges;
    }

    private static final class V26TextBridge implements TextBridge {
        @Override public boolean supportsHexColors() { return true; }
    }

    private static final class V26NbtBridge implements NbtBridge {
        @Override public boolean supportsPersistentDataContainer() { return true; }
    }

    private static final class V26MaterialBridge implements MaterialBridge {
        @Override public boolean usesLegacyNames() { return false; }
    }

    private static final class V26InventoryBridge implements InventoryBridge {
        @Override public boolean supportsRuntimeTitleUpdates() { return true; }
    }
}
