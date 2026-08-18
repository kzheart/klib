package me.kzheart.klib.compat;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 统一管理版本适配器的标识、缓存的基准版本和能力查询；
 * 子类只需提供能力映射和支持范围。
 */
public abstract class AbstractCompatProvider implements CompatProvider {
    private final String id;
    private final ServerVersion version;
    private final Map<Capability<?>, Object> capabilities;

    protected AbstractCompatProvider(
            String id,
            String serverVersion,
            Map<Capability<?>, Object> capabilities
    ) {
        this.id = Objects.requireNonNull(id, "id");
        this.version = ServerVersion.parse(Objects.requireNonNull(serverVersion, "serverVersion"));
        this.capabilities = Collections.unmodifiableMap(new HashMap<Capability<?>, Object>(
                Objects.requireNonNull(capabilities, "capabilities")));
    }

    @Override
    public final String id() {
        return id;
    }

    @Override
    public final ServerVersion version() {
        return version;
    }

    @Override
    public final <T> Optional<T> capability(Capability<T> capability) {
        Objects.requireNonNull(capability, "capability");
        Object implementation = capabilities.get(capability);
        return implementation == null
                ? Optional.<T>empty()
                : Optional.of(capability.type().cast(implementation));
    }
}
