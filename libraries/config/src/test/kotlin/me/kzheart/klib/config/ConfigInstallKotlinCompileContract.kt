package me.kzheart.klib.config

import me.kzheart.klib.KPlugin
import me.kzheart.klib.scope.capability.ConfigCapability

/** 编译契约：用擦除参数避免测试发现阶段加载不在测试运行时中的 Bukkit 宿主类。 */
internal class ConfigInstallKotlinCompileContract {
    fun install(plugin: Any): ConfigCapability {
        val owner = plugin as KPlugin
        return ConfigModule.install(owner, YamlConfigMapper(UnknownKeyPolicy.REJECT))
    }
}
