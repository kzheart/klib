package me.kzheart.klib.command;

/** 内部信号：处理器失败时希望改用指定消息键反馈，而不是通用内部错误。 */
final class CommandFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String messageKey;

    CommandFailure(String messageKey, Throwable cause) {
        super(cause == null ? messageKey : cause.getMessage(), cause);
        this.messageKey = messageKey;
    }

    String messageKey() {
        return messageKey;
    }
}
