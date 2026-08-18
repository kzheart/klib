package me.kzheart.klib.scope;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScopeReverseDisposeTest {

    @Test
    void disposesResourcesAndChildrenInReverseRegistrationOrder() {
        List<String> events = new ArrayList<String>();
        ScopeImpl root = new ScopeImpl("root");

        root.install(() -> events.add("first"));
        root.scope("child", child -> child.install(() -> events.add("child-resource")));
        root.install(() -> events.add("last"));

        root.close();

        assertEquals(Arrays.asList("last", "child-resource", "first"), events);
        assertThrows(IllegalStateException.class, () -> root.install(() -> { }));
    }

    @Test
    void aggregatesFailuresWithoutSkippingLaterResources() {
        List<String> events = new ArrayList<String>();
        ScopeImpl root = new ScopeImpl("root");
        root.install(() -> {
            events.add("first");
            throw new IllegalArgumentException("first failure");
        });
        root.install(() -> {
            events.add("second");
            throw new IllegalStateException("second failure");
        });

        ScopeLifecycleException failure = assertThrows(ScopeLifecycleException.class, root::close);

        assertEquals(Arrays.asList("second", "first"), events);
        assertEquals(2, failure.getSuppressed().length);
    }
}
