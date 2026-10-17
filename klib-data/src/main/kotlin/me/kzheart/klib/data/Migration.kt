package me.kzheart.klib.data

/** 一项有序的结构迁移。 */
class Migration(private val version: Int, private val action: MigrationAction) {
    init { require(version > 0) { "version must be positive" } }
    fun version(): Int = version
    @Throws(Exception::class)
    fun apply(context: TransactionContext) = action.apply(context)

    fun interface MigrationAction {
        @Throws(Exception::class)
        fun apply(context: TransactionContext)
    }
}
