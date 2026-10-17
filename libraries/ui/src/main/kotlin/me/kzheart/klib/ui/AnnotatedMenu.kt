package me.kzheart.klib.ui

import java.lang.reflect.Method
import java.util.Objects
import me.kzheart.klib.reflect.Declarations
import me.kzheart.klib.ui.annotation.Button
import me.kzheart.klib.ui.annotation.Click
import me.kzheart.klib.ui.annotation.Entries
import me.kzheart.klib.ui.annotation.Menu

/** 每个控制器实例只校验一次声明；编译结果由同一模型渲染路径消费。 */
class AnnotatedMenu(private val target: Any) {
    private val menu = target.javaClass.getAnnotation(Menu::class.java)
        ?: throw IllegalArgumentException("${target.javaClass.name}: missing @Menu")
    private val buttons = LinkedHashMap<Char, Method>()
    private val entries = LinkedHashMap<Char, Method>()
    private val clicks = LinkedHashMap<Char, Method>()
    private val slots = LinkedHashMap<Char, MutableList<Int>>()
    init {
        require(menu.layout.size in 1..6) { "Menu requires 1..6 rows" }
        var slot = 0
        for (line in menu.layout) {
            require(line.length == 9) { "Menu row requires 9 characters" }
            for (symbol in line) {
                if (symbol != ' ') slots.getOrPut(symbol) { ArrayList() }.add(slot)
                slot++
            }
        }
        for (method in Declarations.methods(target.javaClass)) {
            val button = method.getAnnotation(Button::class.java)
            val list = method.getAnnotation(Entries::class.java)
            val click = method.getAnnotation(Click::class.java)
            if (listOfNotNull(button, list, click).size > 1) throw Declarations.invalid(method, "only one menu annotation per method")
            if (button != null) {
                if (method.parameterCount != 0 || method.returnType != MenuEntry::class.java) throw Declarations.invalid(method, "button must return MenuEntry without parameters")
                add(buttons, button.value, method)
            }
            if (list != null) {
                if (method.parameterCount != 0 || method.returnType != List::class.java ||
                    method.genericReturnType.typeName != "java.util.List<${MenuEntry::class.java.name}>")
                    throw Declarations.invalid(method, "entries must return List<MenuEntry> without parameters")
                add(entries, list.value, method)
            }
            if (click != null) {
                Declarations.voidMethod(method, 1)
                if (method.parameterTypes[0] != MenuClick::class.java) throw Declarations.invalid(method, "click must accept MenuClick")
                add(clicks, click.value, method)
            }
        }
        for (symbol in slots.keys) require((symbol in buttons) != (symbol in entries)) { "Menu symbol must have exactly one provider: $symbol" }
        for (symbol in clicks.keys) require(symbol in buttons) { "@Click requires @Button; list entries own their actions: $symbol" }
    }
    private fun add(map: MutableMap<Char, Method>, symbol: Char, method: Method) {
        if (symbol !in slots || map.putIfAbsent(symbol, method) != null) throw Declarations.invalid(method, "duplicate or unused symbol $symbol")
    }
    fun render(refresh: Runnable): MenuModel {
        val rendered = LinkedHashMap<Int, MenuEntry>()
        for ((symbol, positions) in slots) {
            val button = buttons[symbol]
            if (button != null) {
                val entry = Objects.requireNonNull(Declarations.invoke(target, button) as MenuEntry?, "button returned null")!!
                val bound = bind(entry, clicks[symbol], refresh)
                positions.forEach { rendered[it] = bound }
            } else {
                val provider = entries.getValue(symbol)
                val values = Declarations.invoke(target, provider) as? List<*> ?: throw Declarations.invalid(provider, "entries returned null")
                if (values.size > positions.size) throw Declarations.invalid(provider, "entries exceed region capacity; paginate first")
                values.forEachIndexed { index, value ->
                    if (value !is MenuEntry) throw Declarations.invalid(provider, "invalid entry")
                    rendered[positions[index]] = bind(value, null, refresh)
                }
            }
        }
        return MenuCompiler.compileSlots(menu.title, menu.layout.size, rendered)
    }
    private fun bind(entry: MenuEntry, method: Method?, refresh: Runnable): MenuEntry {
        val result = MenuEntry.of(entry.item()) { original ->
            val click = MenuClick(original.player(), original.slot(), original.type(), refresh)
            if (method == null) entry.action().accept(click) else Declarations.invoke(target, method, click)
        }
        return entry.clickSound().map { result.withClickSound(it) }.orElse(result)
    }
}
