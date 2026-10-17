package me.kzheart.klib.ui;

import org.bukkit.event.inventory.ClickType;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MenuEventPolicyTest {
    private final MenuModel locked = MenuCompiler.compile(
            MenuTemplate.builder("Locked", 3).build());

    @Test
    void cancelsEveryKnownPathThatCanMoveItemsThroughTheTopInventory() {
        assertTrue(MenuEventPolicy.cancelClick(locked, ClickType.LEFT, 4, locked.size()));
        assertTrue(MenuEventPolicy.cancelClick(
                locked, ClickType.NUMBER_KEY, 4, locked.size()));
        assertTrue(MenuEventPolicy.cancelClick(
                locked, ClickType.SHIFT_LEFT, locked.size() + 3, locked.size()));
        assertTrue(MenuEventPolicy.cancelClick(
                locked, ClickType.DOUBLE_CLICK, locked.size() + 3, locked.size()));
        assertTrue(MenuEventPolicy.cancelDrag(
                locked,
                new LinkedHashSet<Integer>(Arrays.asList(
                        Integer.valueOf(2), Integer.valueOf(locked.size() + 2))),
                locked.size()));
    }

    @Test
    void leavesOrdinaryBottomInventoryOperationsAlone() {
        assertFalse(MenuEventPolicy.cancelClick(
                locked, ClickType.LEFT, locked.size() + 3, locked.size()));
        assertFalse(MenuEventPolicy.cancelClick(
                locked, ClickType.NUMBER_KEY, locked.size() + 3, locked.size()));
        assertFalse(MenuEventPolicy.cancelDrag(
                locked,
                new LinkedHashSet<Integer>(Arrays.asList(
                        Integer.valueOf(locked.size() + 1),
                        Integer.valueOf(locked.size() + 2))),
                locked.size()));
    }

    @Test
    void dropZonesStayAuthoritativeEvenWhenGeneralCancellationIsDisabled() {
        MenuModel unlocked = MenuCompiler.compile(
                MenuTemplate.builder("Unlocked", 1).cancelClicks(false).build());
        assertTrue(MenuEventPolicy.cancelClick(
                unlocked, ClickType.LEFT, 2, unlocked.size(), true, true));
        assertTrue(MenuEventPolicy.cancelClick(
                unlocked, ClickType.NUMBER_KEY, 2, unlocked.size(), true, true));
        assertTrue(MenuEventPolicy.cancelClick(
                unlocked, ClickType.SHIFT_LEFT, unlocked.size() + 1,
                unlocked.size(), false, true));
        assertTrue(MenuEventPolicy.cancelClick(
                unlocked, ClickType.DOUBLE_CLICK, unlocked.size() + 1,
                unlocked.size(), false, true));
        assertFalse(MenuEventPolicy.cancelClick(
                unlocked, ClickType.LEFT, unlocked.size() + 1,
                unlocked.size(), false, true));
    }

    @Test
    void mapsBukkitClickKindsWithoutLosingSecurityRelevantTypes() {
        assertEquals(MenuClickType.SHIFT_LEFT,
                MenuEventPolicy.clickType(ClickType.SHIFT_LEFT));
        assertEquals(MenuClickType.DOUBLE_CLICK,
                MenuEventPolicy.clickType(ClickType.DOUBLE_CLICK));
        assertEquals(MenuClickType.NUMBER_KEY,
                MenuEventPolicy.clickType(ClickType.NUMBER_KEY));
        assertEquals(MenuClickType.UNKNOWN,
                MenuEventPolicy.clickType(ClickType.CREATIVE));
    }
}
