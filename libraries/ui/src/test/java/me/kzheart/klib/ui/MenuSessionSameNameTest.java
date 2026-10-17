package me.kzheart.klib.ui;

import me.kzheart.klib.scope.ScopeImpl;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;

class MenuSessionSameNameTest {
    @Test
    void concurrentSessionsMayShareTheSameBusinessName() {
        ScopeImpl owner = new ScopeImpl("menus");
        MenuModel model = MenuCompiler.compile(MenuTemplate.builder("Shop", 1).build());

        MenuSession first = MenuSession.open(
                owner, "shop", model, items -> Collections.emptyList());
        MenuSession second = MenuSession.open(
                owner, "shop", model, items -> Collections.emptyList());

        assertFalse(first.isClosed());
        assertFalse(second.isClosed());
        first.close(CloseReason.PLAYER);
        second.close(CloseReason.PLAYER);
        owner.close();
    }
}
