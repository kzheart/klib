package me.kzheart.klib.command
import java.util.Collections
class MountedCommand private constructor(private val command: String, private val literals: List<String>, private val handler: Any) {
    fun command(): String = command
    fun literals(): List<String> = literals
    fun handler(): Any = handler
    companion object {
        @JvmStatic fun of(command: String, handler: Any, literal: String, vararg aliases: String): MountedCommand {
            require(handler !is MountedCommand) { "Nested mounts are not supported" }
            return MountedCommand(command, Collections.unmodifiableList(listOf(literal) + aliases), handler)
        }
    }
}
