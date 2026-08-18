package me.kzheart.klib.ui.prompt;

import me.kzheart.klib.scheduler.TaskHandle;
import me.kzheart.klib.scheduler.Ticks;
import me.kzheart.klib.scope.Scope;

import java.util.Objects;

/** 启动取消和超时均归作用域管理的提示会话。 */
public final class ChatPrompt {
    private ChatPrompt() {
    }

    public static <T> PromptSession<T> start(
            Scope scope,
            Ticks timeout,
            PromptParser<T> parser
    ) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(timeout, "timeout");
        PromptSession<T> session = scope.install(new PromptSession<T>(scope, parser));
        session.attachSync(command -> scope.after(Ticks.of(0L), command));
        TaskHandle timeoutTask = scope.after(timeout, session::timeout);
        session.attachTimeout(timeoutTask);
        return session;
    }
}
