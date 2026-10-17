package me.kzheart.klib.ui;
import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
/** Creates a host inventory while keeping Klib ownership, event protection and lifecycle. */
@FunctionalInterface
public interface MenuInventoryFactory {
    Inventory create(InventoryHolder holder, int size, String title);
    static MenuInventoryFactory bukkit() { return Bukkit::createInventory; }
}
