package me.kzheart.klib.command.api;

/**
 * 命令参数的类型化键：既描述命令树上的一个参数节点，也用于从
 * {@link CommandContext} 读取解析结果。
 *
 * <p>该接口对外只读。命令模块只接受由 {@code me.kzheart.klib.command.Arguments}
 * 工厂创建的实现，自行实现本接口的对象无法加入命令树。需要自定义解析或补全时，
 * 使用 {@code Arguments.custom(name, parser, suggester)}，它返回同族的参数实例。
 */
public interface CommandArgument<T> {
    String name();
}
