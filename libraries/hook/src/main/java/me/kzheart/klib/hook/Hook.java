package me.kzheart.klib.hook;

import me.kzheart.klib.scope.Disposable;

/** 已检测到的可选依赖及其适配器公开的值。 */
public interface Hook<T> extends Disposable {

    String dependency();

    boolean available();

    T value();

    DependencyStatus status();

    String detail();
}
