package me.kzheart.klib.item;

import org.bukkit.inventory.ItemStack;
import org.bukkit.entity.Player;

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

    /** 在实际使用者上下文生成一件物品；不需要上下文的提供者沿用基础生成。 */
    default ItemStack create(String id, Player player) {
        return create(id);
    }

    /** 生成一个堆叠。唯一实例提供者可以拒绝大于一的数量，调用方改用逐件批量生成。 */
    default ItemStack createStack(String id, int amount, Player player) {
        if (amount < 1) throw new IllegalArgumentException("Amount must be positive");
        ItemStack item = create(id, player);
        if (item == null) return null;
        item = item.clone();
        item.setAmount(amount);
        return item;
    }

    /** 返回物品在本系统中的 ID；不属于本系统时返回 {@code null}。 */
    String identify(ItemStack item);
}
