package me.kzheart.klib.config
import java.util.TreeMap
class MigrationRunner {
    private val migrations = TreeMap<Int, MutableList<Migration>>()
    fun add(targetVersion: Int, migration: Migration): MigrationRunner = apply {
        require(targetVersion >= 1) { "targetVersion must be positive" }
        migrations.getOrPut(targetVersion) { arrayListOf() }.add(migration)
    }
    fun migrate(document: YamlDocument, currentVersion: Int): Int {
        require(currentVersion >= 0) { "currentVersion must not be negative" }
        if (currentVersion > latestVersion()) throw ConfigException(
            "${document.sourceName()}:_schema-version: document version $currentVersion is newer than supported version ${latestVersion()}")
        val pending = migrations.tailMap(currentVersion, false)
        var expected = currentVersion + 1
        for (target in pending.keys) {
            if (target != expected) throw ConfigException(
                "${document.sourceName()}:_schema-version: missing migration for version $expected before version $target")
            expected++
        }
        var result = currentVersion
        for ((target, steps) in pending) {
            steps.forEach { it.apply(document) }
            result = target
            document.setInteger("_schema-version", result)
        }
        return result
    }
    fun migrate(document: YamlDocument): Int = migrate(document, document.schemaVersion())
    fun latestVersion(): Int = if (migrations.isEmpty()) 0 else migrations.lastKey()
}
