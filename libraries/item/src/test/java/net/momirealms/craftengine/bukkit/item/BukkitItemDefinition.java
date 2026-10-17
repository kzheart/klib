package net.momirealms.craftengine.bukkit.item;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** 测试替身：真实构建被显式计数，确保预览不会进入任一构建重载。 */
public final class BukkitItemDefinition {
    public int builds;
    public int playerBuilds;
    public Player lastPlayer;
    public RuntimeException failure;
    private final Material material;
    private final short marker;

    public BukkitItemDefinition(Material material, short marker) {
        this.material = material;
        this.marker = marker;
    }

    public ItemStack buildBukkitItem() {
        builds++;
        return build();
    }

    public ItemStack buildBukkitItem(Player player) {
        playerBuilds++;
        lastPlayer = player;
        return build();
    }

    @SuppressWarnings("deprecation")
    private ItemStack build() {
        if (failure != null) throw failure;
        return new ItemStack(material, 1, marker);
    }
}
