package me.kzheart.klib.ui;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MenuCompilationPaginationTest {
    @Test
    void charSlotsAndYamlProduceTheSameModelContract() {
        MenuEntry stone = MenuEntry.of(new ItemStack(Material.STONE));
        MenuModel chars = MenuCompiler.compile(MenuTemplate.builder("Menu", 1)
                .layout("x        ")
                .character('x', stone)
                .build());
        MenuModel slots = MenuCompiler.compileSlots("Menu", 1,
                Collections.singletonMap(Integer.valueOf(0), stone));
        Map<String, Object> yaml = new LinkedHashMap<String, Object>();
        yaml.put("title", "Menu");
        yaml.put("rows", Integer.valueOf(1));
        yaml.put("layout", Collections.singletonList("x        "));
        yaml.put("items", Collections.singletonMap("x", "stone"));
        MenuModel fromYaml = MenuCompiler.compileYaml(yaml, id -> stone);

        assertEquals(chars.entries().keySet(), slots.entries().keySet());
        assertEquals(chars.entries().keySet(), fromYaml.entries().keySet());
        assertTrue(chars.cancelClicks());
        assertTrue(chars.shouldCancel(true));
        assertFalse(chars.shouldCancel(false));
    }

    @Test
    void paginatorClampsIndicesAndKeepsStableWindows() {
        Paginator<Integer> paginator = new Paginator<Integer>(
                Arrays.asList(1, 2, 3, 4, 5), 2);

        assertEquals(Arrays.asList(1, 2), paginator.page(-3).values());
        assertEquals(Arrays.asList(5), paginator.page(99).values());
        assertEquals(3, paginator.page(1).count());
        assertTrue(paginator.page(1).hasPrevious());
        assertTrue(paginator.page(1).hasNext());
    }
}
