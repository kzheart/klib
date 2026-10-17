package me.kzheart.klib.data

import java.util.Collections

/** 不可变的有序结构定义；迁移版本可以不连续，但必须正数且唯一。 */
class Schema(name: String?, migrations: List<Migration>) {
    private val name: String
    private val migrations: List<Migration>
    init {
        require(!name.isNullOrBlank()) { "name must not be blank" }
        this.name = name
        val ordered = migrations.sortedBy { it.version() }
        val versions = HashSet<Int>()
        ordered.forEach { require(versions.add(it.version())) { "duplicate migration version ${it.version()}" } }
        this.migrations = Collections.unmodifiableList(ordered)
    }
    fun name(): String = name
    fun version(): Int = migrations.lastOrNull()?.version() ?: 0
    fun migrations(): List<Migration> = migrations
}
