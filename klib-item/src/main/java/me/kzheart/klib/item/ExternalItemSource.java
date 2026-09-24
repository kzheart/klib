package me.kzheart.klib.item;

import org.bukkit.inventory.ItemStack;

/**
 * 单个外部物品系统的适配器，例如 MMOItems 或 NeigeItems。
 *
 * <p>在 {@link ExternalItems} 中以 {@link #prefix()} 注册，配置里用 {@code prefix:id} 引用物品。
 */
public interface ExternalItemSource {
    /** 物品引用前缀，只能包含小写字母、数字、下划线和短横线。 */
    String prefix();

    /** 用于诊断输出的插件名称。 */
    String plugin();

    /** 按 ID 生成新物品；ID 不存在时返回 {@code null}。 */
    ItemStack create(String id);

    /** 返回物品在本系统中的 ID；不属于本系统时返回 {@code null}。 */
    String identify(ItemStack item);
}
