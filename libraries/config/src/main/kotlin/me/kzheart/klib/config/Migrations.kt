package me.kzheart.klib.config
class Migrations private constructor() {
    companion object {
        @JvmStatic fun rename(sourcePath: String, targetPath: String): Migration {
            require(sourcePath.trim().isNotEmpty() && targetPath.trim().isNotEmpty()) { "Migration paths must not be blank" }
            return Migration { it.rename(sourcePath, targetPath) }
        }
    }
}
