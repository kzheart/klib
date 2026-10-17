package me.kzheart.klib.ui.prompt

import me.kzheart.klib.scheduler.Ticks
import me.kzheart.klib.scope.Scope
import java.util.concurrent.Executor

/** 启动取消和超时均归作用域管理的提示会话。 */
class ChatPrompt private constructor() {
    companion object {
        @JvmStatic fun <T> start(scope: Scope, timeout: Ticks, parser: PromptParser<T>): PromptSession<T> {
            val session = scope.install(PromptSession(scope, parser))
            session.attachSync(Executor { command -> scope.after(Ticks.of(0), command) })
            session.attachTimeout(scope.after(timeout) { session.timeout() })
            return session
        }
    }
}
