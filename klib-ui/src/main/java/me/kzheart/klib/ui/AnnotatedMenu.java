package me.kzheart.klib.ui;

import java.lang.reflect.Method;
import java.util.*;
import me.kzheart.klib.reflect.Declarations;
import me.kzheart.klib.ui.annotation.*;

/** Validated per-instance menu declaration, rendered through MenuCompiler. */
public final class AnnotatedMenu {
    private final Object target;
    private final Menu menu;
    private final Map<Character, Method> buttons = new LinkedHashMap<Character, Method>();
    private final Map<Character, Method> entries = new LinkedHashMap<Character, Method>();
    private final Map<Character, Method> clicks = new LinkedHashMap<Character, Method>();
    private final Map<Character, List<Integer>> slots = new LinkedHashMap<Character, List<Integer>>();

    public AnnotatedMenu(Object target) {
        this.target = Objects.requireNonNull(target, "target");
        menu = target.getClass().getAnnotation(Menu.class);
        if (menu == null) throw new IllegalArgumentException(target.getClass().getName() + ": missing @Menu");
        if (menu.layout().length < 1 || menu.layout().length > 6) throw new IllegalArgumentException("Menu requires 1..6 rows");
        int slot = 0;
        for (String line : menu.layout()) {
            if (line.length() != 9) throw new IllegalArgumentException("Menu row requires 9 characters");
            for (char symbol : line.toCharArray()) {
                if (symbol != ' ') slots.computeIfAbsent(symbol, k -> new ArrayList<Integer>()).add(slot);
                slot++;
            }
        }
        for (Method method : Declarations.methods(target.getClass())) {
            Button button = method.getAnnotation(Button.class);
            Entries list = method.getAnnotation(Entries.class);
            Click click = method.getAnnotation(Click.class);
            int count = (button == null ? 0 : 1) + (list == null ? 0 : 1) + (click == null ? 0 : 1);
            if (count > 1) throw Declarations.invalid(method, "only one menu annotation per method");
            if (button != null) {
                if (method.getParameterCount() != 0 || method.getReturnType() != MenuEntry.class) throw Declarations.invalid(method, "button must return MenuEntry without parameters");
                add(buttons, button.value(), method);
            }
            if (list != null) {
                if (method.getParameterCount() != 0 || method.getReturnType() != List.class
                        || !method.getGenericReturnType().getTypeName().equals("java.util.List<" + MenuEntry.class.getName() + ">")) {
                    throw Declarations.invalid(method, "entries must return List<MenuEntry> without parameters");
                }
                add(entries, list.value(), method);
            }
            if (click != null) {
                Declarations.voidMethod(method, 1);
                if (method.getParameterTypes()[0] != MenuClick.class) throw Declarations.invalid(method, "click must accept MenuClick");
                add(clicks, click.value(), method);
            }
        }
        for (Character symbol : slots.keySet()) {
            if (buttons.containsKey(symbol) == entries.containsKey(symbol)) throw new IllegalArgumentException("Menu symbol must have exactly one provider: " + symbol);
        }
        for (Character symbol : clicks.keySet()) {
            if (!buttons.containsKey(symbol)) throw new IllegalArgumentException("@Click requires @Button; list entries own their actions: " + symbol);
        }
    }
    private void add(Map<Character, Method> map, char symbol, Method method) {
        if (!slots.containsKey(symbol) || map.putIfAbsent(symbol, method) != null) throw Declarations.invalid(method, "duplicate or unused symbol " + symbol);
    }
    public MenuModel render(Runnable refresh) {
        Objects.requireNonNull(refresh, "refresh");
        Map<Integer, MenuEntry> rendered = new LinkedHashMap<Integer, MenuEntry>();
        for (Map.Entry<Character, List<Integer>> region : slots.entrySet()) {
            char symbol = region.getKey();
            List<Integer> positions = region.getValue();
            Method button = buttons.get(symbol);
            if (button != null) {
                MenuEntry entry = Objects.requireNonNull((MenuEntry) Declarations.invoke(target, button), "button returned null");
                Method click = clicks.get(symbol);
                MenuEntry bound = bind(entry, click, refresh);
                for (Integer position : positions) rendered.put(position, bound);
            } else {
                Object value = Declarations.invoke(target, entries.get(symbol));
                if (!(value instanceof List<?>)) throw Declarations.invalid(entries.get(symbol), "entries returned null");
                List<?> values = (List<?>) value;
                if (values.size() > positions.size()) throw Declarations.invalid(entries.get(symbol), "entries exceed region capacity; paginate first");
                for (int i = 0; i < values.size(); i++) {
                    if (!(values.get(i) instanceof MenuEntry)) throw Declarations.invalid(entries.get(symbol), "invalid entry");
                    rendered.put(positions.get(i), bind((MenuEntry) values.get(i), null, refresh));
                }
            }
        }
        return MenuCompiler.compileSlots(menu.title(), menu.layout().length, rendered);
    }
    private MenuEntry bind(MenuEntry entry, Method method, Runnable refresh) {
        MenuEntry result = MenuEntry.of(entry.item(), original -> {
            MenuClick click = new MenuClick(original.player(), original.slot(), original.type(), refresh);
            if (method == null) entry.action().accept(click);
            else Declarations.invoke(target, method, click);
        });
        return entry.clickSound().isPresent() ? result.withClickSound(entry.clickSound().get()) : result;
    }
}
