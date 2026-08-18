package me.kzheart.klib.remote;

import java.util.Map;
import java.util.Objects;
import me.kzheart.klib.diagnostic.DiagnosticSource;

/** 把 Klib 模块的轻量内存快照接入 Incident Contributor。 */
public final class KlibDiagnosticContributor implements DiagnosticContributor {
    private final DiagnosticSource source;

    public KlibDiagnosticContributor(DiagnosticSource source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    @Override public String name() {
        return source.diagnosticName();
    }

    @Override public Map<String, ?> contribute(Context context) {
        return source.diagnosticSnapshot();
    }
}
