package me.kzheart.klib.compat

/** Selects the newest declared baseline without hiding missing or unsupported adapters. */
class CompatResolver(providers: Collection<out CompatProvider>) {
    private val providers = providers.toMutableList()
    init {
        require(this.providers.isNotEmpty()) { "At least one compatibility provider is required" }
        val versions = HashSet<ServerVersion>()
        val ids = HashSet<String>()
        this.providers.forEach { provider ->
            require(ids.add(provider.id())) { "Duplicate compatibility provider id: ${provider.id()}" }
            require(versions.add(provider.version())) { "Duplicate compatibility provider version: ${provider.version()}" }
            require(provider.supports(provider.version())) { "Compatibility provider does not support its baseline version: ${provider.id()}" }
        }
        this.providers.sortBy { it.version() }
    }
    fun resolve(serverVersion: String): CompatProvider = resolve(ServerVersion.parse(serverVersion))
    fun resolve(serverVersion: ServerVersion): CompatProvider = providers.lastOrNull {
        it.version() <= serverVersion && it.supports(serverVersion)
    } ?: throw IllegalArgumentException(describeNoMatch(serverVersion))
    fun capabilityMatrix(): CompatCapabilityMatrix = CompatCapabilityMatrix.inspect(providers)
    private fun describeNoMatch(version: ServerVersion): String =
        "No compatible provider for server $version; registered providers: " + providers.joinToString(", ") {
            val reason = if (it.version() > version) "baseline newer than the server" else "does not declare support for this version"
            "${it.id()} (baseline ${it.version()}, $reason)"
        } + ". Either the matching compatibility implementation is not bundled, or this version falls outside every declared support range."
}
