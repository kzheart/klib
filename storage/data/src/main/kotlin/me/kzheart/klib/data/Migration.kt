package me.kzheart.klib.data

import java.util.Objects

/** Kotlin owns validation and execution; Java preserves the nested SAM platform-type contract. */
internal class MigrationState(val version: Int, action: Migration.MigrationAction?) {
    init { require(version > 0) { "version must be positive" } }
    private val action: Migration.MigrationAction = Objects.requireNonNull(action, "action")!!
    @Throws(Exception::class)
    fun apply(context: TransactionContext?) = action.apply(context)
}
