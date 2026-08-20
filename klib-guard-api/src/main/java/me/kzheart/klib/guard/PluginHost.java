package me.kzheart.klib.guard;

import java.io.File;
import java.util.logging.Logger;
import me.kzheart.klib.guard.kether.KetherInteropBroker;
import org.bukkit.Server;
import org.bukkit.plugin.java.JavaPlugin;

/** 由 KlibGuard 中央门户持有的商品级主机上下文。 */
public interface PluginHost {
    String productId();
    long generation();
    KetherInteropBroker ketherInteropBroker();
    JavaPlugin plugin();
    Server server();
    File dataFolder();
    Logger logger();
}
