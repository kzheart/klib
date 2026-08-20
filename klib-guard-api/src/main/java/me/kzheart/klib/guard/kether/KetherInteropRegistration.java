package me.kzheart.klib.guard.kether;

/** 门户中一个商品代次的可撤销 Kether 路由注册。 */
public interface KetherInteropRegistration extends AutoCloseable {

    void publish(String namespace, String... actions);

    void withdraw(String namespace, String... actions);

    boolean isActive();

    @Override
    void close();
}
