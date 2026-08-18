package me.kzheart.klib.compat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 已安装兼容提供者的不可变能力快照。 */
public final class CompatCapabilityMatrix {
    private final List<Row> rows;
    private final Map<String, Row> rowsByProvider;

    private CompatCapabilityMatrix(List<Row> rows) {
        this.rows = Collections.unmodifiableList(rows);
        Map<String, Row> indexed = new LinkedHashMap<String, Row>();
        for (Row row : rows) {
            indexed.put(row.providerId(), row);
        }
        rowsByProvider = Collections.unmodifiableMap(indexed);
    }

    public static CompatCapabilityMatrix inspect(Collection<? extends CompatProvider> providers) {
        Objects.requireNonNull(providers, "providers");
        List<Row> rows = new ArrayList<Row>();
        for (CompatProvider provider : providers) {
            Objects.requireNonNull(provider, "provider");
            Map<Capability<?>, Boolean> availability = new LinkedHashMap<Capability<?>, Boolean>();
            for (Capability<?> capability : Capabilities.values()) {
                availability.put(capability, hasCapability(provider, capability));
            }
            rows.add(new Row(provider.id(), provider.version(), availability));
        }
        return new CompatCapabilityMatrix(rows);
    }

    public List<Row> rows() {
        return rows;
    }

    public Row row(String providerId) {
        Row row = rowsByProvider.get(Objects.requireNonNull(providerId, "providerId"));
        if (row == null) {
            throw new IllegalArgumentException("Unknown compatibility provider: " + providerId
                    + "; known provider ids: " + rowsByProvider.keySet());
        }
        return row;
    }

    private static <T> boolean hasCapability(CompatProvider provider, Capability<T> capability) {
        return provider.capability(capability).isPresent();
    }

    /** 单个提供者在其基准服务器版本上的能力可用情况。 */
    public static final class Row {
        private final String providerId;
        private final ServerVersion version;
        private final Map<Capability<?>, Boolean> availability;

        private Row(String providerId, ServerVersion version, Map<Capability<?>, Boolean> availability) {
            this.providerId = Objects.requireNonNull(providerId, "providerId");
            this.version = Objects.requireNonNull(version, "version");
            this.availability = Collections.unmodifiableMap(
                    new LinkedHashMap<Capability<?>, Boolean>(availability));
        }

        public String providerId() {
            return providerId;
        }

        public ServerVersion version() {
            return version;
        }

        public boolean has(Capability<?> capability) {
            Boolean present = availability.get(Objects.requireNonNull(capability, "capability"));
            return present != null && present.booleanValue();
        }

        public Map<Capability<?>, Boolean> availability() {
            return availability;
        }
    }
}
