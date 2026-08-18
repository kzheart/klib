package me.kzheart.klib.remote;

import java.util.LinkedHashMap;
import java.util.Map;

/** Remote 自动附带的四项运行环境摘要。 */
public final class RemoteEnvironment {
    private final String pluginVersion;
    private final String minecraft;
    private final String java;
    private final String os;

    public RemoteEnvironment(String pluginVersion, String minecraft, String java, String os) {
        this.pluginVersion = Texts.requireText(pluginVersion, "pluginVersion");
        this.minecraft = Texts.requireText(minecraft, "minecraft");
        this.java = Texts.requireText(java, "java");
        this.os = Texts.requireText(os, "os");
    }

    public String pluginVersion() { return pluginVersion; }
    public String minecraft() { return minecraft; }
    public String javaVersion() { return java; }
    public String os() { return os; }

    Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("plugin_version", pluginVersion);
        result.put("minecraft", minecraft);
        result.put("java", java);
        result.put("os", os);
        return result;
    }
}
