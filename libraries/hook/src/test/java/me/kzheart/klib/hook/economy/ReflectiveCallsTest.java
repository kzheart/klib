package me.kzheart.klib.hook.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ReflectiveCallsTest {
    @Test
    void cachedLookupKeepsDistinctPrimitiveBoxingAndNullFingerprints() {
        Api api = new Api();
        assertEquals("int:3", ReflectiveCalls.invoke(api, "value", Integer.valueOf(3)));
        assertEquals("long:4", ReflectiveCalls.invoke(api, "value", Long.valueOf(4)));
        assertNull(ReflectiveCalls.invoke(api, "nullable", (Object) null));
        assertFalse(ReflectiveCalls.hasMethod(api, "value", (Object) null));
        assertFalse(ReflectiveCalls.hasMethod(api, "missing"));
        assertFalse(ReflectiveCalls.hasMethod(api, "missing"));
        assertEquals("fallback", ReflectiveCalls.invokeEither(api, "missing", "fallback"));
    }

    @Test
    void transactionResultsSupportBooleanMethodFieldAndVoid() {
        assertTrue(ReflectiveCalls.succeeded(null));
        assertTrue(ReflectiveCalls.succeeded(Boolean.TRUE));
        assertFalse(ReflectiveCalls.succeeded(Boolean.FALSE));
        assertTrue(ReflectiveCalls.succeeded(new MethodResult()));
        assertTrue(ReflectiveCalls.succeeded(new FieldResult()));
        assertThrows(IllegalStateException.class, () -> ReflectiveCalls.succeeded(new Object()));
    }

    @Test
    void invocationPreservesRuntimeFailureButWrapsCheckedFailure() {
        IllegalArgumentException runtime = assertThrows(IllegalArgumentException.class,
                () -> ReflectiveCalls.invoke(new Api(), "runtimeFailure"));
        assertEquals("runtime", runtime.getMessage());
        IllegalStateException checked = assertThrows(IllegalStateException.class,
                () -> ReflectiveCalls.invoke(new Api(), "checkedFailure"));
        assertEquals("checked", checked.getCause().getMessage());
    }

    public static final class Api {
        public String value(int value) { return "int:" + value; }
        public String value(long value) { return "long:" + value; }
        public String nullable(String value) { return value; }
        public String fallback() { return "fallback"; }
        public void runtimeFailure() { throw new IllegalArgumentException("runtime"); }
        public void checkedFailure() throws Exception { throw new Exception("checked"); }
    }

    public static final class MethodResult {
        public boolean transactionSuccess() { return true; }
    }

    public static final class FieldResult {
        public boolean transactionSuccess = true;
    }
}
