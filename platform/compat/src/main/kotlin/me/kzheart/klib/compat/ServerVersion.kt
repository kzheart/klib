package me.kzheart.klib.compat

class ServerVersion private constructor(private val major: Int, private val minor: Int, private val patch: Int) : Comparable<ServerVersion> {
    init { require(major >= 0 && minor >= 0 && patch >= 0) { "Version parts must be non-negative" } }
    fun major(): Int = major
    fun minor(): Int = minor
    fun patch(): Int = patch
    override fun compareTo(other: ServerVersion): Int = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
    override fun equals(other: Any?): Boolean = this === other || other is ServerVersion && major == other.major && minor == other.minor && patch == other.patch
    override fun hashCode(): Int = 31 * (31 * major + minor) + patch
    override fun toString(): String = "$major.$minor.$patch"
    companion object {
        private val version = Regex("""(?:^|[^0-9])(\d+)\.(\d+)(?:\.(\d+))?""")
        @JvmStatic fun of(major: Int, minor: Int, patch: Int): ServerVersion = ServerVersion(major, minor, patch)
        @JvmStatic fun parse(value: String): ServerVersion {
            val match = version.find(value) ?: throw IllegalArgumentException("Cannot parse server version: $value")
            return of(match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groups[3]?.value?.toInt() ?: 0)
        }
    }
}
