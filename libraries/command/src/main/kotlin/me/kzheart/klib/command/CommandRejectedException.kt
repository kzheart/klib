package me.kzheart.klib.command
/** Expected business refusal whose message can safely be shown to the sender. */
class CommandRejectedException(message: String) : RuntimeException(message) {
    companion object { private const val serialVersionUID = 1L }
}
