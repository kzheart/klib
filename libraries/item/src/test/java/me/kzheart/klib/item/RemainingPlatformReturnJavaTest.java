package me.kzheart.klib.item;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RemainingPlatformReturnJavaTest {
    @Test void boxedModelDataKeepsNullAndStaticDeclaration() throws Exception {
        assertNull(Items.customModelData(null));
        assertNull(Items.customModelData(new ItemStack(Material.AIR)));
        Method method = Items.class.getDeclaredMethod("customModelData", ItemStack.class);
        assertEquals(Integer.class, method.getReturnType());
        assertTrue(Modifier.isStatic(method.getModifiers()));
        assertFalse(Modifier.isFinal(method.getModifiers()));
    }
}
