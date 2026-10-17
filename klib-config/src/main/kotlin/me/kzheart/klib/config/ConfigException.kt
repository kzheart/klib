package me.kzheart.klib.config
/** Base failure for configuration parsing, mapping and migration. */
open class ConfigException : RuntimeException {
    constructor(message: String?) : super(message)
    constructor(message: String?, cause: Throwable?) : super(message, cause)
    companion object { private const val serialVersionUID = 1L }
}
