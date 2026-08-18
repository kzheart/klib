package me.kzheart.klib.command.api;

import org.bukkit.command.CommandSender;

import java.util.Optional;

/**
 * 一次命令执行的解析结果。
 *
 * <p>参数既可以按建树时的 {@link CommandArgument} 实例读取，也可以按参数名读取。
 * 包装类工厂（如 {@code Arguments.optional(...)}）会返回新的参数实例，此时
 * {@link #get(CommandArgument)} 必须使用包装后的实例，按名读取则不受影响。
 */
public interface CommandContext {
    CommandSender sender();

    String label();

    <T> T get(CommandArgument<T> argument);

    /**
     * 按参数名查找本次解析路径上的值。同名参数出现在同一路径的多个层级时，返回最深处的值。
     *
     * <p>值为 {@code null}（可选参数默认值为 {@code null}）时返回 {@link Optional#empty()}。
     *
     * @throws UnsupportedOperationException 实现未提供按名读取时
     */
    default Optional<Object> find(String name) {
        throw new UnsupportedOperationException(
                "按名读取参数需要 klib 提供的命令上下文实现: " + getClass().getName());
    }

    /**
     * 按参数名读取本次解析路径上的值并转换为期望类型。
     *
     * @throws IllegalArgumentException 该名称未在本次解析中出现
     * @throws ClassCastException       值与 {@code type} 不兼容
     */
    default <T> T get(String name, Class<T> type) {
        if (type == null) {
            throw new NullPointerException("type");
        }
        Optional<Object> value = find(name);
        if (!value.isPresent()) {
            throw new IllegalArgumentException("argument was not parsed: " + name);
        }
        return type.cast(value.get());
    }
}
