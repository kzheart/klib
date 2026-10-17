package net.milkbowl.vault.economy;

import org.bukkit.entity.Player;

/** 仅用于验证按类型名称发现服务的最小 Vault 测试夹具。 */
public interface Economy {
    double getBalance(Player player);

    String format(double amount);
}
