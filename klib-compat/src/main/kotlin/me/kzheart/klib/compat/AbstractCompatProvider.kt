package me.kzheart.klib.compat

import java.util.Optional

/** Stores the common capability graph; version modules supply only their range and differences. */
abstract class AbstractCompatProvider protected constructor(private val id: String, serverVersion: String, capabilities: Map<Capability<*>, @JvmSuppressWildcards Any>) : CompatProvider {
    private val version = ServerVersion.parse(serverVersion)
    private val capabilities = HashMap(capabilities)
    final override fun id(): String = id
    final override fun version(): ServerVersion = version
    @Suppress("UNCHECKED_CAST")
    final override fun <T> capability(capability: Capability<T>): Optional<T> =
        Optional.ofNullable(capabilities[capability]?.let { capability.type().cast(it) }) as Optional<T>
}
