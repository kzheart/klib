package me.kzheart.klib.script;

/** command 动作使用的宿主集成。 */
@FunctionalInterface
public interface CommandSink {

    Object dispatch(Object sender, String command);

    /** {@code command ... as op}：临时以管理员身份执行；默认不支持。 */
    default Object dispatchAsOperator(Object sender, String command) {
        throw new UnsupportedOperationException("command as op is not supported by this host");
    }
}
