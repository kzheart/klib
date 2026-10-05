package me.kzheart.klib.command.api;

import java.util.function.Consumer;
import java.util.function.Supplier;

public interface CommandSpec {
    /** Descend through a space-separated literal path; existing literal nodes are reused. */
    default CommandSpec route(String path) {
        throw new UnsupportedOperationException("Flat routes require klib-command");
    }

    /** Append one argument and return its child node for flat chaining. */
    default <T> CommandSpec argument(CommandArgument<T> argument) {
        throw new UnsupportedOperationException("Flat arguments require klib-command");
    }

    CommandSpec description(String description);

    CommandSpec permission(String permission);

    CommandSpec playerOnly();

    CommandSpec executes(CommandHandler handler);

    /** 为当前节点及其子节点选择异常反馈；子节点可覆盖。未配置时继承模块策略。 */
    CommandSpec errorHandler(CommandErrorHandler handler);

    CommandSpec helpStyle(CommandHelpStyle style);

    /** 允许配置重载替换样式；不重建命令树。提供器在主线程读取。 */
    CommandSpec helpStyle(Supplier<CommandHelpStyle> style);

    CommandSpec literal(String literal, Consumer<? super CommandSpec> configure);

    <T> CommandSpec argument(
            CommandArgument<T> argument,
            Consumer<? super CommandSpec> configure);
}
