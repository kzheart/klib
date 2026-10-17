package me.kzheart.klib.remote

import me.kzheart.klib.diagnostic.DiagnosticSource

/** 将模块内存快照接入 Incident 的薄适配器。 */
class KlibDiagnosticContributor(private val source: DiagnosticSource) : DiagnosticContributor {
    override fun name(): String = source.diagnosticName()
    override fun contribute(context: DiagnosticContributor.Context?): Map<String, *> = source.diagnosticSnapshot()
}
