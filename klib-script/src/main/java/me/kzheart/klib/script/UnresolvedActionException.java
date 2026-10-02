package me.kzheart.klib.script;

/** 没有任何互操作容器认领该语句名；容错解析时据此把词元当作字面量。 */
final class UnresolvedActionException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    UnresolvedActionException(String action) {
        super("No shared Kether action resolved: " + action);
    }
}
