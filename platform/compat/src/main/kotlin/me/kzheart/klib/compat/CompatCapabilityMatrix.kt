package me.kzheart.klib.compat

import java.util.Collections

class CompatCapabilityMatrix private constructor(rows: List<Row>) {
    private val rows = Collections.unmodifiableList(rows)
    private val rowsByProvider = rows.associateBy { it.providerId() }
    fun rows(): List<Row> = rows
    fun row(providerId: String): Row = rowsByProvider[providerId] ?: throw IllegalArgumentException(
        "Unknown compatibility provider: $providerId; known provider ids: ${rowsByProvider.keys}")
    class Row internal constructor(private val providerId: String, private val version: ServerVersion,
                                   availability: Map<Capability<*>, Boolean>) {
        private val availability = Collections.unmodifiableMap(LinkedHashMap(availability))
        fun providerId(): String = providerId
        fun version(): ServerVersion = version
        fun has(capability: Capability<*>): Boolean = availability[capability] == true
        fun availability(): Map<Capability<*>, Boolean> = availability
    }
    companion object {
        @JvmStatic fun inspect(providers: Collection<out CompatProvider>): CompatCapabilityMatrix =
            CompatCapabilityMatrix(providers.map { provider ->
                Row(provider.id(), provider.version(), Capabilities.values().associateWith { provider.capability(it).isPresent })
            })
    }
}
