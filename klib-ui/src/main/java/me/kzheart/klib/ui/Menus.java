package me.kzheart.klib.ui;

import java.util.*;
import me.kzheart.klib.KPlugin;
import me.kzheart.klib.component.KContext;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Menu service bound once to a plugin or component. One controller instance per open session. */
public final class Menus {
    private final MenuRenderer renderer;
    private final Set<Object> active = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
    private Menus(MenuRenderer renderer) { this.renderer = renderer; }
    public static Menus install(KPlugin plugin) { return install(plugin.context(), plugin); }
    public static Menus install(KContext context, Plugin plugin) {
        return new Menus(MenuRenderer.install(context.scope(), plugin));
    }
    public MenuHolder open(Player player, Object controller) {
        if (!org.bukkit.Bukkit.isPrimaryThread()) throw new IllegalStateException("Opening a menu requires the server main thread");
        AnnotatedMenu declaration = new AnnotatedMenu(controller);
        synchronized (active) {
            if (!active.add(controller)) throw new IllegalStateException("Menu controller already has an open session");
        }
        MenuHolder[] holder = new MenuHolder[1];
        Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            if (holder[0] != null && !holder[0].session().isClosed()) holder[0].refresh(declaration.render(refresh[0]));
        };
        try {
            holder[0] = renderer.open(player, controller.getClass().getSimpleName(), declaration.render(refresh[0]), session ->
                    session.scope().install(() -> { synchronized (active) { active.remove(controller); } }));
            return holder[0];
        } catch (RuntimeException | Error failure) {
            synchronized (active) { active.remove(controller); }
            throw failure;
        }
    }
}
