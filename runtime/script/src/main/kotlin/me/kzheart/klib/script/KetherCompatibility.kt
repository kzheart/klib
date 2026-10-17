package me.kzheart.klib.script

import me.kzheart.klib.scope.Scope
import me.kzheart.klib.script.kether.core.QuestActionParser

/** Explicit legacy syntax is scoped; ordinary engine defaults stay unchanged. */
class KetherCompatibility private constructor() {
    companion object {
        @JvmStatic fun installLegacyCases(scope: Scope, registry: StatementRegistry): StatementRegistration =
            registry.registerKether(scope, "klib", "case", QuestActionParser.of { reader ->
                StructuredScriptActions.caseAction(reader, true, registry.registeredNames())
            })
    }
}
