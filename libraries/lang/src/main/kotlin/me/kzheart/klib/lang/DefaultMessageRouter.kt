package me.kzheart.klib.lang

/** 向控制台发送纯文本，向其他 CommandSender 发送带旧版颜色的文本。 */
class DefaultMessageRouter : MessageRouter {
    override fun route(recipient: MessageRecipient, message: RichText) {
        recipient.sendLegacy(if (recipient.isConsole()) message.plainText() else message.legacyText())
    }
    override fun route(recipient: MessageRecipient, route: MessageRoute, message: RichText) {
        val sink = recipient.handle() as? MessageRouteSink
        if (!recipient.isConsole() && sink != null) sink.send(route, message)
        else route(recipient, message)
    }
}
