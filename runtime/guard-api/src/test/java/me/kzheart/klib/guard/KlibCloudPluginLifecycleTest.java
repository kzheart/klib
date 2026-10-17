package me.kzheart.klib.guard;

import java.io.File;
import java.util.logging.Logger;
import me.kzheart.klib.guard.kether.KetherInteropBroker;
import me.kzheart.klib.scope.Scope;
import org.bukkit.Server;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class KlibCloudPluginLifecycleTest {
    @Test void illegalTransitionsAndRepeatedDisableKeepOriginalContract() throws Exception {
        Probe plugin = new Probe();
        assertThrows(IllegalStateException.class, plugin::onEnable);
        assertThrows(IllegalStateException.class, () -> plugin.onLoad(null));
        assertThrows(IllegalStateException.class, plugin::host);
        plugin.onDisable();
        PluginHost host = new Host();
        plugin.onLoad(host);
        assertSame(host, plugin.host());
        assertEquals(host.dataFolder(), plugin.dataFolder());
        assertNotNull(plugin.logger());
        assertThrows(IllegalStateException.class, () -> plugin.onLoad(host));
        assertThrows(IllegalStateException.class, plugin::rootScope);
        plugin.onDisable();
        assertEquals(1, plugin.disables);
        assertThrows(IllegalStateException.class, plugin::host);
        assertThrows(IllegalStateException.class, plugin::logger);
        plugin.onDisable();
        assertEquals(1, plugin.disables);
    }
    @Test void checkedLoadFailureRollsBackBeforeRetry() throws Exception {
        Probe plugin = new Probe();
        plugin.loadFailure = new Exception("load");
        assertSame(plugin.loadFailure, assertThrows(Exception.class, () -> plugin.onLoad(new Host())));
        assertThrows(IllegalStateException.class, plugin::host);
        plugin.loadFailure = null;
        plugin.onLoad(new Host());
        plugin.onDisable();
    }
    @Test void linkageLoadFailureAlsoRollsBack() throws Exception {
        Probe plugin = new Probe();
        plugin.linkageFailure = new LinkageError("link");
        assertSame(plugin.linkageFailure, assertThrows(LinkageError.class, () -> plugin.onLoad(new Host())));
        assertThrows(IllegalStateException.class, plugin::logger);
        plugin.linkageFailure = null;
        plugin.onLoad(new Host());
        plugin.onDisable();
    }
    @Test void disableFailureStillClearsHostAndAllowsNextLoad() throws Exception {
        Probe plugin = new Probe();
        plugin.onLoad(new Host());
        plugin.disableFailure = new Exception("disable");
        assertSame(plugin.disableFailure, assertThrows(Exception.class, plugin::onDisable));
        assertThrows(IllegalStateException.class, plugin::host);
        plugin.disableFailure = null;
        plugin.onLoad(new Host());
        plugin.onDisable();
    }
    private static final class Probe extends KlibCloudPlugin {
        private Exception loadFailure, disableFailure;
        private LinkageError linkageFailure;
        private int disables;
        @Override protected void setup(Scope scope) { }
        @Override protected void load() throws Exception {
            if (loadFailure != null) throw loadFailure;
            if (linkageFailure != null) throw linkageFailure;
        }
        @Override protected void disable() throws Exception {
            disables++;
            if (disableFailure != null) throw disableFailure;
        }
    }
    private static final class Host implements PluginHost {
        @Override public String productId() { return "lifecycle-test"; }
        @Override public long generation() { return 1; }
        @Override public KetherInteropBroker ketherInteropBroker() { return null; }
        @Override public JavaPlugin plugin() { return null; }
        @Override public Server server() { return null; }
        @Override public File dataFolder() { return new File("."); }
        @Override public Logger logger() { return Logger.getLogger("guard-lifecycle-test"); }
    }
}
