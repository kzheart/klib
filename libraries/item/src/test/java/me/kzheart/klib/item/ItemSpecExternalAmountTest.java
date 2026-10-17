package me.kzheart.klib.item;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ItemSpecExternalAmountTest {
    private final ExternalItemProvider provider = new ExternalItemProvider() {
        @Override
        public boolean matches(String provider, String id, ItemStack item) {
            return true;
        }

        @Override
        public ItemStack create(String provider, String id) {
            return new ItemStack(Material.STONE, 16);
        }
    };

    @Test
    void externalItemsKeepTheirAmountUnlessItWasExplicitlyConfigured() {
        assertEquals(16, ItemSpec.builder().external("demo", "stone", provider)
                .build().create().getAmount());
        assertEquals(3, ItemSpec.builder().external("demo", "stone", provider)
                .amount(3).build().create().getAmount());
    }

    @Test
    void textualAmountIsRejectedInsteadOfSilentlyBecomingOne() {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put("amount", "16");
        assertThrows(IllegalArgumentException.class,
                () -> ItemSpec.from(values));
    }
}
