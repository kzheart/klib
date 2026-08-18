package me.kzheart.klib.hook.papi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import me.kzheart.klib.scope.Disposable;
import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.Test;

class BukkitPapiRegistrarTest {

    @Test
    void registersARealPersistentExpansionAndUnregistersIdempotently() {
        RecordingLifecycle lifecycle = new RecordingLifecycle(true);
        BukkitPapiRegistrar registrar = new BukkitPapiRegistrar("klib", "1.0", lifecycle);
        PapiExpansion expansion = new PapiDsl()
                .key("points", player -> Integer.valueOf(42))
                .build();

        Disposable registration = registrar.register("gather", expansion);

        assertEquals("gather", invoke(lifecycle.expansion, "getIdentifier"));
        assertEquals("klib", invoke(lifecycle.expansion, "getAuthor"));
        assertEquals("1.0", invoke(lifecycle.expansion, "getVersion"));
        assertEquals(Boolean.FALSE, invoke(lifecycle.expansion, "persist"));
        assertEquals("42", invoke(
                lifecycle.expansion,
                "onRequest",
                new Class<?>[] {OfflinePlayer.class, String.class},
                null,
                "points"));

        registration.dispose();
        registration.dispose();
        assertTrue(lifecycle.unregistered.get());
    }

    private static Object invoke(Object target, String method) {
        return invoke(target, method, new Class<?>[0]);
    }

    private static Object invoke(
            Object target,
            String method,
            Class<?>[] parameterTypes,
            Object... arguments
    ) {
        try {
            Method selected = target.getClass().getMethod(method, parameterTypes);
            return selected.invoke(target, arguments);
        } catch (NoSuchMethodException failure) {
            throw new AssertionError(failure);
        } catch (IllegalAccessException failure) {
            throw new AssertionError(failure);
        } catch (InvocationTargetException failure) {
            throw new AssertionError(failure.getCause());
        }
    }

    @Test
    void persistIsConfigurable() {
        RecordingLifecycle lifecycle = new RecordingLifecycle(true);
        BukkitPapiRegistrar registrar = new BukkitPapiRegistrar("klib", "1.0", true, lifecycle);
        registrar.register("gather", new PapiDsl().key("value", player -> "ok").build());

        assertEquals(Boolean.TRUE, invoke(lifecycle.expansion, "persist"));
    }

    @Test
    void rejectsARegistrationRefusedByPlaceholderApi() {
        BukkitPapiRegistrar registrar = new BukkitPapiRegistrar(
                "klib",
                "1.0",
                new RecordingLifecycle(false));
        PapiExpansion expansion = new PapiDsl().key("value", player -> "ok").build();

        assertThrows(IllegalStateException.class, () -> registrar.register("gather", expansion));
    }

    private static final class RecordingLifecycle
            implements BukkitPapiRegistrar.ExpansionLifecycle {
        private final boolean registrationResult;
        private final AtomicBoolean unregistered = new AtomicBoolean();
        private Object expansion;

        private RecordingLifecycle(boolean registrationResult) {
            this.registrationResult = registrationResult;
        }

        @Override
        public boolean register(Object candidate) {
            expansion = candidate;
            return registrationResult;
        }

        @Override
        public void unregister(Object candidate) {
            assertFalse(unregistered.getAndSet(true));
            assertEquals(expansion, candidate);
        }
    }
}
