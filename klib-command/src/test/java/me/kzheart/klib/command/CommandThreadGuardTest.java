package me.kzheart.klib.command;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import static org.junit.jupiter.api.Assertions.*;

class CommandThreadGuardTest {
    @Test void publicRawEntryPointsAndRegistrationRejectOffMainThread() throws Exception {
        PaperLifecycleCommandBridgeTest.Fixture fixture = new PaperLifecycleCommandBridgeTest.Fixture();
        CommandSpecImpl spec = CommandSpecImpl.command("demo");
        fixture.bridge.register("demo", spec, new CommandDispatcher(spec));
        Server async = (Server) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Server.class},
                (proxy, method, args) -> "isPrimaryThread".equals(method.getName()) ? false : null);
        Field field = Bukkit.class.getDeclaredField("server"); field.setAccessible(true);
        Object original = field.get(null);
        try {
            field.set(null, async);
            assertThrows(IllegalStateException.class, () -> fixture.bridge.register("another", spec, new CommandDispatcher(spec)));
            assertThrows(IllegalStateException.class, () -> fixture.registrar.last.execute(
                    () -> TestSenders.console().sender(), new String[0]));
            assertThrows(IllegalStateException.class, () -> fixture.registrar.last.suggest(
                    () -> TestSenders.console().sender(), new String[]{""}));
        } finally {
            field.set(null, original);
        }
    }
}
