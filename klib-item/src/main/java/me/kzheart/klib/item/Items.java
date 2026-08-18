package me.kzheart.klib.item;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;
import java.util.Objects;

/** 创建和编辑物品的入口。 */
public final class Items {
    private Items() {
    }

    public static ItemBuilder edit(ItemStack item) {
        return new ItemBuilder(Objects.requireNonNull(item, "item").clone());
    }

    public static ItemBuilder of(Material material) {
        return new ItemBuilder(new ItemStack(Objects.requireNonNull(material, "material")));
    }

    public static ItemBuilder of(String material) {
        return of(resolveMaterial(material));
    }

    public static Material resolveMaterial(String input) {
        Objects.requireNonNull(input, "material");
        String name = input.trim();
        int separator = name.indexOf(':');
        if (separator >= 0) {
            name = name.substring(separator + 1);
        }
        Material material = Material.matchMaterial(name.toUpperCase(Locale.ROOT).replace('-', '_'));
        if (material == null) {
            throw new IllegalArgumentException("Unknown material: " + input);
        }
        return material;
    }
}
