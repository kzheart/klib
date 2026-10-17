package me.kzheart.klib.reflect;

import java.lang.reflect.Method;
import java.util.List;

/** 公开反射入口；Java 声明保留既有平台返回类型，算法由 Kotlin 实现。 */
public final class Declarations {
    private Declarations() { }

    public static List<Method> methods(Class<?> type) { return DeclarationsSupport.methods(type); }
    public static void voidMethod(Method method, int parameters) { DeclarationsSupport.voidMethod(method, parameters); }
    public static IllegalArgumentException invalid(Method method, String reason) { return DeclarationsSupport.invalid(method, reason); }
    public static Object invoke(Object target, Method method, Object... args) { return DeclarationsSupport.invoke(target, method, args); }
}
