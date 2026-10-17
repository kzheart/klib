package me.kzheart.klib.guard.kether;

/** Guard 门户持有的商品级 Kether 路由边界。 */
public interface KetherInteropBroker {

    /**
     * 将一个商品代次绑定到门户。商品身份、代次与类加载器必须来自同一次已认证加载。
     */
    KetherInteropRegistration attach(
            String productId,
            long generation,
            ClassLoader productLoader,
            KetherInteropEndpoint endpoint);
}
