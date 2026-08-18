package me.kzheart.klib.guard;

/** 由已认证远程插件制品实现的生命周期接口。 */
public interface RemotePluginEntrypoint {
    void onLoad(PluginHost host) throws Exception;
    void onEnable() throws Exception;
    void onDisable() throws Exception;
}
