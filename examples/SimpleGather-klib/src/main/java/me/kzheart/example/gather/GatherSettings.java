package me.kzheart.example.gather;

import java.util.LinkedHashMap;
import java.util.Map;

/** 迁移测试夹具使用的可重载配置。 */
public final class GatherSettings {
    public boolean debug = false;
    public String toolMaterial = "IRON_PICKAXE";
    public String rewardMaterial = "COBBLESTONE";
    public int rewardAmount = 1;
    public int toolDurability = 64;
    public int gatherHealth = 3;
    public Map<String, Object> diagnostics = new LinkedHashMap<String, Object>();

    public boolean autoReport() {
        Object value = diagnostics.get("auto-report");
        return !(value instanceof Boolean) || ((Boolean) value).booleanValue();
    }

    public String collectorEndpoint() {
        return text("endpoint");
    }

    public String collectorToken() {
        return text("token");
    }

    private String text(String key) {
        Object value = diagnostics.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }
}
