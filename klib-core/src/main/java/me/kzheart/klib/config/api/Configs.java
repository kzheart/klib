package me.kzheart.klib.config.api;
import java.util.Objects;
import me.kzheart.klib.config.annotation.ConfigFile;
import me.kzheart.klib.scope.Scope;
public final class Configs {
    private final Scope owner;
    public Configs(Scope owner) { this.owner = Objects.requireNonNull(owner, "owner"); }
    public <T> ConfigDocument<T> load(Class<T> type) {
        ConfigFile file = type.getAnnotation(ConfigFile.class);
        if (file == null) throw new IllegalArgumentException(type.getName() + ": missing @ConfigFile");
        return load(type, file.value());
    }
    public <T> ConfigDocument<T> load(Class<T> type, String path) { return owner.config(type, path); }
}
