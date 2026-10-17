package me.kzheart.klib.command
internal class CommandFailure(private val messageKey: String, cause: Throwable?) : RuntimeException(if (cause == null) messageKey else cause.message, cause) {
    fun messageKey(): String = messageKey
    companion object { private const val serialVersionUID = 1L }
}
