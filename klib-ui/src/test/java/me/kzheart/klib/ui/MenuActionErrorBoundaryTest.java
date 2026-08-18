package me.kzheart.klib.ui;

import me.kzheart.klib.KLogger;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

class MenuActionErrorBoundaryTest {
    @Test
    void actionFailureIsLoggedAndDeliveredToConfigurableBoundary() {
        KLogger logger = new KLogger(Logger.getLogger("menu-action-test"));
        AtomicInteger logged = new AtomicInteger();
        logger.onError((message, failure) -> logged.incrementAndGet());
        AtomicReference<Throwable> reported = new AtomicReference<Throwable>();
        MenuErrorHandler handler = (player, model, click, failure) -> reported.set(failure);
        MenuModel model = MenuCompiler.compile(MenuTemplate.builder("Shop", 1).build());
        IllegalStateException expected = new IllegalStateException("purchase failed");
        MenuEntry entry = MenuEntry.of(new ItemStack(Material.STONE), click -> {
            throw expected;
        });
        MenuClick click = new MenuClick(player(), 0);

        boolean succeeded = MenuRenderer.dispatchAction(logger, handler, model, entry, click);

        assertFalse(succeeded);
        assertSame(expected, reported.get());
        org.junit.jupiter.api.Assertions.assertEquals(1, logged.get());
    }

    private static Player player() {
        return (Player) Proxy.newProxyInstance(
                MenuActionErrorBoundaryTest.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, arguments) -> {
                    if (method.getReturnType() == Boolean.TYPE) {
                        return Boolean.FALSE;
                    }
                    if (method.getReturnType().isPrimitive()) {
                        return Integer.valueOf(0);
                    }
                    return null;
                });
    }
}
