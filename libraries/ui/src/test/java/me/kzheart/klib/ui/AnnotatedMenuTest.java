package me.kzheart.klib.ui;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.lang.reflect.Proxy;
import me.kzheart.klib.ui.annotation.*;
import me.kzheart.klib.scope.ScopeImpl;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AnnotatedMenuTest {
    @Menu(title="Dungeons", layout={"dd     nn"}) public static class Screen {
        int page;
        @Entries('d') public List<MenuEntry> rows() { return Collections.singletonList(MenuEntry.of(new ItemStack(Material.STONE, page + 1))); }
        @Button('n') public MenuEntry next() { return MenuEntry.of(new ItemStack(Material.DIAMOND)); }
        @Click('n') public void next(MenuClick click) { page++; click.refresh(); }
    }
    @Test void refreshRebuildsEntriesWithoutReplacingSessionAndInstancesAreIndependent() {
        Screen screen = new Screen(); AnnotatedMenu declaration = new AnnotatedMenu(screen);
        Screen other = new Screen();
        AtomicInteger refreshed = new AtomicInteger();
        MenuModel before = declaration.render(refreshed::incrementAndGet);
        assertEquals(1, before.entry(0).get().item().getAmount()); assertFalse(before.entry(1).isPresent());
        Player player = (Player) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Player.class}, (p,m,a) -> null);
        before.entry(7).get().action().accept(new MenuClick(player, 7));
        assertEquals(1, refreshed.get()); assertEquals(0, other.page);
        MenuModel after = declaration.render(refreshed::incrementAndGet);
        ScopeImpl root = new ScopeImpl("menu");
        MenuSession session = MenuSession.open(root, "menu", before, items -> Collections.emptyList());
        session.updateModel(after); assertEquals(2, session.model().entry(0).get().item().getAmount());
        session.close(CloseReason.PLAYER);
        assertThrows(IllegalStateException.class, () -> session.updateModel(before)); root.close();
    }
    @Menu(title="Invalid", layout={"x        "}) public static class Unbound { }
    @Menu(title="Invalid", layout={"x"}) public static class BadWidth { }
    @Menu(title="Invalid", layout={"x        "}) public static class Overflow {
        @Entries('x') public List<MenuEntry> entries() { return Arrays.asList(MenuEntry.of(new ItemStack(Material.STONE)), MenuEntry.of(new ItemStack(Material.STONE))); }
    }
    @Test void rejectsInvalidLayoutsAndOverflow() {
        assertThrows(IllegalArgumentException.class, () -> new AnnotatedMenu(new Unbound()));
        assertThrows(IllegalArgumentException.class, () -> new AnnotatedMenu(new BadWidth()));
        assertThrows(IllegalArgumentException.class, () -> new AnnotatedMenu(new Overflow()).render(() -> { }));
    }
    @Test void refreshCannotChangeInventoryShape() {
        ScopeImpl root = new ScopeImpl("menu");
        MenuSession session = MenuSession.open(root, "menu", MenuCompiler.compileSlots("A", 1, Collections.emptyMap()), items -> Collections.emptyList());
        assertThrows(IllegalArgumentException.class, () -> session.updateModel(MenuCompiler.compileSlots("B", 2, Collections.emptyMap())));
        assertEquals("A", session.model().title()); root.close();
    }
}
