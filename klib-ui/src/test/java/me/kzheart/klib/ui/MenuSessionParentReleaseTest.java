package me.kzheart.klib.ui;

import me.kzheart.klib.scope.ScopeImpl;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MenuSessionParentReleaseTest {
    @Test
    void closedSessionsDoNotAccumulateInParentScope() throws Exception {
        ScopeImpl owner = new ScopeImpl("menus");
        MenuModel model = MenuCompiler.compile(MenuTemplate.builder("Shop", 1).build());
        int baseline = resourceCount(owner);

        for (int index = 0; index < 5; index++) {
            MenuSession session = MenuSession.open(
                    owner, "shop", model, items -> Collections.emptyList());
            session.close(CloseReason.PLAYER);
            assertEquals(baseline, resourceCount(owner));
        }

        owner.close();
    }

    private static int resourceCount(ScopeImpl scope) throws Exception {
        Field field = ScopeImpl.class.getDeclaredField("resources");
        field.setAccessible(true);
        return ((List<?>) field.get(scope)).size();
    }
}
