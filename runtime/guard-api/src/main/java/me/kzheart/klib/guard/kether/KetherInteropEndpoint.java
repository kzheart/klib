package me.kzheart.klib.guard.kether;

import java.util.concurrent.CompletionStage;

/**
 * 商品私有 Kether 运行时向父加载器公开的句柄式调用边界。
 *
 * <p>实现不得返回私有的 parser、action 或 frame。门户只持有 {@code long} action 句柄，
 * 商品内部对象始终留在商品类加载器内。</p>
 */
public interface KetherInteropEndpoint {

    /** 把外部容器发布的 action 导入商品注册表。 */
    void addActions(
            KetherInteropPeer peer,
            String namespace,
            String[] actions);

    /** 撤销指定外部容器发布的 action。 */
    void removeActions(
            KetherInteropPeer peer,
            String namespace,
            String[] actions);

    /** 解析商品发布的 action，返回仅对当前商品代次有效的不透明句柄。 */
    long resolve(
            KetherInteropPeer peer,
            String consumerName,
            Object reader,
            String action,
            String namespace);

    /** 使用外部 frame 执行先前解析的 action。 */
    CompletionStage<Object> process(
            KetherInteropPeer peer,
            String consumerName,
            long actionHandle,
            Object frame);

    /** 提前释放一个 action 句柄；重复释放必须安全。 */
    void release(long actionHandle);

    /** 使全部句柄和进行中的 future 失效并释放商品引用；重复调用必须安全。 */
    void close();
}
