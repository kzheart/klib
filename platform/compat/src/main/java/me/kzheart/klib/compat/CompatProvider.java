package me.kzheart.klib.compat;

import java.util.Optional;

/** 不暴露 Bukkit 或实现类型的版本适配器契约。 */
public interface CompatProvider {
    String id();

    ServerVersion version();

    boolean supports(ServerVersion serverVersion);

    <T> Optional<T> capability(Capability<T> capability);
}
