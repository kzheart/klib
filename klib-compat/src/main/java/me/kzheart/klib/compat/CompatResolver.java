package me.kzheart.klib.compat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 选择不高于当前服务器版本的最新适配器。 */
public final class CompatResolver {
    private final List<CompatProvider> providers;

    public CompatResolver(Collection<? extends CompatProvider> providers) {
        Objects.requireNonNull(providers, "providers");
        this.providers = new ArrayList<CompatProvider>(providers);
        if (this.providers.isEmpty()) {
            throw new IllegalArgumentException("At least one compatibility provider is required");
        }
        Set<ServerVersion> versions = new HashSet<ServerVersion>();
        Set<String> ids = new HashSet<String>();
        for (CompatProvider provider : this.providers) {
            Objects.requireNonNull(provider, "provider");
            if (!ids.add(provider.id())) {
                throw new IllegalArgumentException(
                        "Duplicate compatibility provider id: " + provider.id());
            }
            if (!versions.add(provider.version())) {
                throw new IllegalArgumentException(
                        "Duplicate compatibility provider version: " + provider.version());
            }
            if (!provider.supports(provider.version())) {
                throw new IllegalArgumentException(
                        "Compatibility provider does not support its baseline version: " + provider.id());
            }
        }
        this.providers.sort(Comparator.comparing(CompatProvider::version));
    }

    public CompatProvider resolve(String serverVersion) {
        return resolve(ServerVersion.parse(serverVersion));
    }

    public CompatProvider resolve(ServerVersion serverVersion) {
        Objects.requireNonNull(serverVersion, "serverVersion");
        CompatProvider selected = null;
        for (CompatProvider provider : providers) {
            if (provider.version().compareTo(serverVersion) <= 0
                    && provider.supports(serverVersion)
                    && (selected == null
                    || provider.version().compareTo(selected.version()) > 0)) {
                selected = provider;
            }
        }
        if (selected == null) {
            throw new IllegalArgumentException(describeNoMatch(serverVersion));
        }
        return selected;
    }

    /** 说明每个已注册提供者为什么落选，便于区分“没打包实现”和“版本落在空隙”。 */
    private String describeNoMatch(ServerVersion serverVersion) {
        StringBuilder message = new StringBuilder("No compatible provider for server ")
                .append(serverVersion)
                .append("; registered providers: ");
        for (int index = 0; index < providers.size(); index++) {
            CompatProvider provider = providers.get(index);
            if (index > 0) {
                message.append(", ");
            }
            message.append(provider.id())
                    .append(" (baseline ")
                    .append(provider.version())
                    .append(", ")
                    .append(provider.version().compareTo(serverVersion) > 0
                            ? "baseline newer than the server"
                            : "does not declare support for this version")
                    .append(')');
        }
        return message.append(". Either the matching compatibility implementation is not bundled, ")
                .append("or this version falls outside every declared support range.")
                .toString();
    }

    public CompatCapabilityMatrix capabilityMatrix() {
        return CompatCapabilityMatrix.inspect(providers);
    }
}
