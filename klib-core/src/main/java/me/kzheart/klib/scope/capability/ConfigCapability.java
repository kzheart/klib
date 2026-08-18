package me.kzheart.klib.scope.capability;

import me.kzheart.klib.config.api.ConfigDocument;
import me.kzheart.klib.scope.Scope;

public interface ConfigCapability {
    <T> ConfigDocument<T> load(Scope owner, Class<T> type, String path);
}
