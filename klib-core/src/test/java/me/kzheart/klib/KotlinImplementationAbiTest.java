package me.kzheart.klib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;
import me.kzheart.klib.scheduler.AsyncTasks;
import me.kzheart.klib.scope.ScopeImpl;
import me.kzheart.klib.scope.ScopeLifecycleException;

class KotlinImplementationAbiTest {
    // This source must still compile: Kotlin @JvmStatic would make the base
    // method final and reject an established Java subclass hiding pattern.
    static final class JavaPluginConsumer extends KPlugin {
        public static JavaPluginConsumer instance() { return null; }
    }

    @Test void preservesJavaStaticHidingBoundary() throws Exception {
        assertFalse(Modifier.isFinal(KPlugin.class.getDeclaredMethod("instance").getModifiers()));
        assertTrue(Modifier.isStatic(JavaPluginConsumer.class.getDeclaredMethod("instance").getModifiers()));
    }

    @Test void keepsSerializableLifecycleExceptionIdentity() {
        assertEquals(1L, java.io.ObjectStreamClass.lookup(ScopeLifecycleException.class).getSerialVersionUID());
    }

    @Test void exceptionUnwrapKeepsOriginalIdentity() {
        IllegalStateException failure = new IllegalStateException("source");
        assertEquals(failure, AsyncTasks.unwrap(new java.util.concurrent.CompletionException(failure)));
    }

    @Test void javaCallbacksRemainOrdinaryFunctionalInterfaces() {
        ScopeImpl scope = ScopeImpl.create("java-consumer", root -> root.registerCapability(String.class, "value"));
        assertEquals("value", scope.requireCapability(String.class));
        scope.close();
        assertTrue(scope.isClosed());
    }
}
