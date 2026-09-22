package me.kzheart.klib.component;
import java.util.*;
import me.kzheart.klib.scope.ScopeImpl;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ComponentsTest {
    public static class Feature extends KComponent {
        final List<String> log;
        Feature(List<String> log) { this.log = log; }
        @OnStart public void start() { log.add("start"); }
        @Override protected void setup() { context().scope().install(() -> log.add("resource")); log.add("setup"); }
        @OnStop public void stop() { log.add("stop"); }
    }
    @Test void shutdownReleasesRegistrationsBeforeBusinessResources() {
        ScopeImpl root = new ScopeImpl("root"); List<String> log = new ArrayList<String>();
        Feature feature = new Feature(log);
        ComponentHandle handle = new KContext(root).components().install(feature);
        assertEquals(Arrays.asList("start", "setup"), log);
        handle.close(); handle.close();
        assertEquals(Arrays.asList("start", "setup", "resource", "stop"), log);
        assertTrue(handle.isClosed()); assertThrows(IllegalStateException.class, feature::context); root.close();
    }
    public static class Failing extends Feature {
        Failing(List<String> log) { super(log); }
        @Override protected void setup() { super.setup(); throw new IllegalStateException("failed"); }
    }
    @Test void setupFailureRollsBackResourcesAndCallsStop() {
        ScopeImpl root = new ScopeImpl("root"); List<String> log = new ArrayList<String>();
        assertThrows(IllegalStateException.class, () -> new Components(root).install(new Failing(log)));
        assertEquals(Arrays.asList("start", "setup", "resource", "stop"), log); root.close();
    }
    public static class Invalid { @OnStart private void start() { fail("must not run"); } }
    @Test void validatesBeforeStartingAndRejectsReusingComponent() {
        ScopeImpl root = new ScopeImpl("root"); Components components = new Components(root);
        assertThrows(IllegalArgumentException.class, () -> components.install(new Invalid()));
        Feature feature = new Feature(new ArrayList<String>()); components.install(feature);
        assertThrows(IllegalStateException.class, () -> components.install(feature)); root.close();
    }
    @Test void parentCloseStopsNestedComponentsInReverseOrder() {
        ScopeImpl root = new ScopeImpl("root"); List<String> log = new ArrayList<String>();
        new Components(root).install(new Feature(log)); root.close();
        assertEquals(Arrays.asList("start", "setup", "resource", "stop"), log);
    }
}
