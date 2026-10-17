package me.kzheart.klib.reflect;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RemainingPlatformReturnJavaTest {
    public static final class Target {
        final RuntimeException runtime = new IllegalArgumentException("runtime");
        final Error error = new AssertionError("error");
        final IOException checked = new IOException("checked");
        public Object missing() { return null; }
        public void runtime() { throw runtime; }
        public void error() { throw error; }
        public void checked() throws IOException { throw checked; }
    }

    @Test void nullAndExceptionIdentityRemainUnchanged() throws Exception {
        Target target = new Target();
        assertNull(Declarations.invoke(target, Target.class.getMethod("missing")));
        assertSame(target.runtime, assertThrows(IllegalArgumentException.class,
                () -> Declarations.invoke(target, Target.class.getMethod("runtime"))));
        assertSame(target.error, assertThrows(AssertionError.class,
                () -> Declarations.invoke(target, Target.class.getMethod("error"))));
        assertSame(target.checked, assertThrows(IllegalStateException.class,
                () -> Declarations.invoke(target, Target.class.getMethod("checked"))).getCause());
    }

    @Test void publicVarargsMethodRemainsDeclaredAndNonFinal() throws Exception {
        Method method = Declarations.class.getDeclaredMethod("invoke", Object.class, Method.class, Object[].class);
        assertTrue(method.isVarArgs());
        assertTrue(Modifier.isStatic(method.getModifiers()));
        assertFalse(Modifier.isFinal(method.getModifiers()));
        assertEquals(0, method.getExceptionTypes().length);
        assertEquals(Object.class, method.getReturnType());
    }
}
