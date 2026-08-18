package me.kzheart.klib.lang;

/** 向控制台发送纯文本，向其他 CommandSender 发送带旧版颜色的文本。 */
public final class DefaultMessageRouter implements MessageRouter {
    @Override
    public void route(MessageRecipient recipient, RichText message) {
        recipient.sendLegacy(recipient.isConsole() ? message.plainText() : message.legacyText());
    }

    @Override
    public void route(MessageRecipient recipient, MessageRoute route, RichText message) {
        if (!recipient.isConsole() && recipient.handle() instanceof MessageRouteSink) {
            ((MessageRouteSink) recipient.handle()).send(route, message);
            return;
        }
        route(recipient, message);
    }
}
