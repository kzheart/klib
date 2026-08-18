package me.kzheart.klib.script;

import me.kzheart.klib.scope.Disposable;

/** 可撤销的语句注册。 */
public interface StatementRegistration extends Disposable {

    String namespace();

    String name();

    boolean isRegistered();
}
