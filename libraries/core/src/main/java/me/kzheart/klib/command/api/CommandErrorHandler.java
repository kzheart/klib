package me.kzheart.klib.command.api;

/** 业务命令异常反馈。Klib 已记录异常，处理器自行决定提示、关闭界面或不发送消息。 */
@FunctionalInterface
public interface CommandErrorHandler {
    void handle(CommandContext context, Throwable failure);
}
