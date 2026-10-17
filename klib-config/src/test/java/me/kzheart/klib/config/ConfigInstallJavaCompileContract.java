package me.kzheart.klib.config;

import me.kzheart.klib.KPlugin;
import me.kzheart.klib.scope.capability.ConfigCapability;

/** 编译契约：用擦除参数避免测试发现阶段加载不在测试运行时中的 Bukkit 宿主类。 */
final class ConfigInstallJavaCompileContract {
    ConfigCapability install(Object plugin) {
        KPlugin owner = (KPlugin) plugin;
        return ConfigModule.install(owner, new YamlConfigMapper(UnknownKeyPolicy.REJECT));
    }
}
