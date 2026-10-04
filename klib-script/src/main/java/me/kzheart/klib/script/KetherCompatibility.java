package me.kzheart.klib.script;

import java.util.Objects;
import me.kzheart.klib.scope.Scope;
import me.kzheart.klib.script.kether.core.QuestActionParser;

/** Explicit legacy configuration compatibility; ordinary engine defaults stay unchanged. */
public final class KetherCompatibility {
    private KetherCompatibility() { }

    /**
     * Accepts an optional {@code ->} after case's {@code else}, and uppercase display-label
     * words on a single branch line (for example {@code VERY SLOW}). Quoted values and
     * executable actions retain their normal behavior. The registration belongs to scope.
     * Unknown standalone literals still require the engine's toleranceParser option.
     *
     * @param scope owner of the compatibility registration
     * @param registry registry used by the engine
     * @return the registration, which can also be disposed individually
     */
    public static StatementRegistration installLegacyCases(Scope scope, StatementRegistry registry) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(registry, "registry");
        return registry.registerKether(scope, "klib", "case", QuestActionParser.of(reader ->
                StructuredScriptActions.caseAction(reader, true, registry.registeredNames())));
    }
}
