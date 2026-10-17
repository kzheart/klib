package me.kzheart.klib.lang

import java.lang.reflect.InvocationTargetException

/** 兼容 CommandSender 且不在编译期依赖 Bukkit 的接收者。 */
class MessageRecipient private constructor(private val handle: Any, private val console: Boolean) {
    fun handle(): Any = handle
    fun isConsole(): Boolean = console
    fun sendLegacy(text: String) {
        try {
            handle.javaClass.getMethod("sendMessage", String::class.java).invoke(handle, text)
        } catch (failure: NoSuchMethodException) {
            throw IllegalArgumentException("Recipient has no sendMessage(String): ${handle.javaClass.name}", failure)
        } catch (failure: IllegalAccessException) {
            throw IllegalStateException("Cannot access sendMessage(String)", failure)
        } catch (failure: InvocationTargetException) {
            throw IllegalStateException("sendMessage(String) failed", failure.cause)
        }
    }
    companion object {
        @JvmStatic fun commandSender(sender: Any): MessageRecipient = MessageRecipient(sender, isConsoleType(sender.javaClass))
        @JvmStatic fun of(handle: Any, console: Boolean): MessageRecipient = MessageRecipient(handle, console)
        private fun isConsoleType(type: Class<*>): Boolean =
            type.simpleName == "ConsoleCommandSender" || type.interfaces.any(::isConsoleType) ||
                (type.superclass?.let(::isConsoleType) ?: false)
    }
}
