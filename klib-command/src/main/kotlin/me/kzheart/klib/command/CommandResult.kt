package me.kzheart.klib.command
import me.kzheart.klib.lang.RichText
class CommandResult private constructor(private val status: Status, private val message: RichText?) {
    enum class Status { SUCCESS, HELP, NO_PERMISSION, PLAYER_ONLY, INVALID_ARGUMENT, UNKNOWN_ARGUMENT, INCOMPLETE, FAILED }
    fun status(): Status = status
    fun message(): RichText? = message
    companion object {
        @JvmStatic fun success(): CommandResult = CommandResult(Status.SUCCESS, null)
        @JvmStatic fun message(status: Status, message: RichText?): CommandResult = CommandResult(status, message)
    }
}
