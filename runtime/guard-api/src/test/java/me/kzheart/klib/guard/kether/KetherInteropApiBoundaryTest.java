package me.kzheart.klib.guard.kether;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class KetherInteropApiBoundaryTest {

    @Test
    void publicAbiDoesNotExposeProductKetherTypes() {
        List<Class<?>> apiTypes = Arrays.<Class<?>>asList(
                KetherInteropBroker.class,
                KetherInteropEndpoint.class,
                KetherInteropPeer.class,
                KetherInteropRegistration.class,
                KetherInteropResult.class,
                KetherInteropProtocol.class);

        for (Class<?> type : apiTypes) {
            for (Method method : type.getDeclaredMethods()) {
                assertParentVisible(method.getReturnType(), method.toGenericString());
                for (Class<?> parameter : method.getParameterTypes()) {
                    assertParentVisible(parameter, method.toGenericString());
                }
            }
        }
    }

    private static void assertParentVisible(Class<?> type, String method) {
        Class<?> component = type;
        while (component.isArray()) {
            component = component.getComponentType();
        }
        assertFalse(component.getName().startsWith("me.kzheart.klib.script"), method);
        assertFalse(component.getName().startsWith("taboolib."), method);
    }
}
