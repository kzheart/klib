package me.kzheart.klib.config

/** A mapping failure with a source path and optional one-based YAML location. */
class ConfigMappingException : ConfigException {
    private val sourceName: String
    private val path: String
    private val line: Int
    private val column: Int
    constructor(sourceName: String, path: String, detail: String?) : this(sourceName, path, -1, -1, detail)
    constructor(sourceName: String, path: String, detail: String?, cause: Throwable?) : this(sourceName, path, -1, -1, detail, cause)
    constructor(sourceName: String, path: String, line: Int, column: Int, detail: String?) :
        super("${ConfigLocations.prefix(sourceName, path, line, column)}: $detail") {
        this.sourceName = sourceName; this.path = path; this.line = line; this.column = column
    }
    constructor(sourceName: String, path: String, line: Int, column: Int, detail: String?, cause: Throwable?) :
        super("${ConfigLocations.prefix(sourceName, path, line, column)}: $detail", cause) {
        this.sourceName = sourceName; this.path = path; this.line = line; this.column = column
    }
    fun sourceName(): String = sourceName
    fun path(): String = path
    fun line(): Int = line
    fun column(): Int = column
    fun hasLocation(): Boolean = line > 0
    companion object {
        const val UNKNOWN_POSITION = -1
        private const val serialVersionUID = 1L
    }
}
