package me.kzheart.klib.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import me.kzheart.klib.hook.cost.CostType;
import me.kzheart.klib.hook.cost.CostTypes;
import me.kzheart.klib.hook.cost.RewardType;
import me.kzheart.klib.hook.cost.RewardTypes;
import me.kzheart.klib.hook.economy.Currency;
import me.kzheart.klib.hook.papi.PapiDsl;
import me.kzheart.klib.hook.papi.PapiRegistrar;
import me.kzheart.klib.scope.Disposable;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class HookJavaInteropTest {
    @Test
    void genericFactoriesAndSamTypesRemainNaturalJavaApis() {
        Supplier<String> detector = () -> "ready";
        Hook<CharSequence> hook = Hooks.<CharSequence>orNoop("test", detector, new StringBuilder("noop"));
        CostType cost = argument -> CostTypes.permission().parse(argument);
        RewardType reward = argument -> RewardTypes.message().parse(argument);
        PapiRegistrar registrar = (identifier, expansion) -> () -> { };

        assertEquals("ready", hook.value());
        assertEquals("权限 sample.use", cost.parse("sample.use").describe());
        assertEquals("消息", reward.parse("hello").describe());
        assertTrue(registrar.available());
        assertEquals("value", new PapiDsl().key("key", player -> "value").build().resolve(null, "key"));
    }

    @Test
    void javaDefaultMethodsAndStaticEntryPointsKeepTheirJvmShape() throws Exception {
        assertTrue(Currency.class.getMethod("has", Player.class, BigDecimal.class).isDefault());
        assertTrue(Currency.class.getMethod("minorUnit").isDefault());
        assertTrue(PapiRegistrar.class.getMethod("available").isDefault());
        assertTrue(Modifier.isStatic(Hooks.class.getMethod("available", String.class, Object.class).getModifiers()));
        assertTrue(Modifier.isFinal(ResolvedHook.class.getModifiers()));
        assertFalse(Modifier.isFinal(Hook.class.getModifiers()));
    }

    @Test
    void disposableHookStillOwnsItsValueExactlyOnce() {
        AtomicInteger calls = new AtomicInteger();
        Hook<Disposable> hook = Hooks.available("test", calls::incrementAndGet);
        hook.dispose();
        hook.dispose();
        assertEquals(1, calls.get());
    }
}
