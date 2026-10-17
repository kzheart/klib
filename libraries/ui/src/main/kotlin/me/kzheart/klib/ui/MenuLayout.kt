package me.kzheart.klib.ui

import java.util.Collections
import java.util.function.Consumer

/** 已校验的不可变配置布局；加载候选成功后才由调用方发布到配置快照。 */
class MenuLayout private constructor(
    private val title: String,
    private val rows: Int,
    private val cancelClicks: Boolean,
    private val sourcePath: String,
    private val positions: Map<Char, List<Int>>,
    private val functions: Set<Char>,
    private val items: Map<Char, MenuEntry>,
    private val staticSlots: Map<Int, MenuEntry>
) {
    fun slots(symbol: Char): List<Int> = positions[symbol]
        ?: throw IllegalArgumentException("$sourcePath.layout: character '$symbol' is absent")

    fun compile(bindings: Consumer<MenuBindings>): MenuModel {
        val bound = MenuBindings(sourcePath, positions, functions, items, staticSlots)
        try {
            bindings.accept(bound)
            val template = MenuTemplate.builder(title, rows).cancelClicks(cancelClicks)
            bound.finish().forEach { (slot, entry) -> template.slot(slot, entry) }
            return MenuCompiler.compile(template.build())
        } finally { bound.close() }
    }

    companion object {
        @JvmStatic
        fun from(yaml: Map<String, *>, sourcePath: String, functionalCharacters: Set<Char>, resolver: YamlItemResolver): MenuLayout {
            require(sourcePath.isNotBlank()) { "sourcePath must not be blank" }
            fun fail(path: String, message: String): Nothing = throw IllegalArgumentException("$sourcePath.$path: $message")
            val supported = setOf("title", "rows", "cancel-clicks", "layout", "items", "slots")
            yaml.keys.firstOrNull { it !in supported }?.let { fail(it, "unknown menu key") }
            val title = yaml["title"]?.let { it as? String ?: fail("title", "must be a string") } ?: "Menu"
            val rows = yaml["rows"]?.let { it.toString().toIntOrNull() ?: fail("rows", "must be an integer") } ?: 1
            if (rows !in 1..6) fail("rows", "must be between 1 and 6")
            val cancel = when (val value = yaml["cancel-clicks"]) {
                null -> true
                true, "true" -> true
                false, "false" -> false
                else -> fail("cancel-clicks", "must be true or false")
            }
            val functions = LinkedHashSet(functionalCharacters)
            if (' ' in functions) fail("layout", "space cannot be a functional character")
            val positions = LinkedHashMap<Char, MutableList<Int>>()
            val layout = yaml["layout"]?.let { it as? List<*> ?: fail("layout", "must be a list") } ?: emptyList<Any>()
            if (layout.isNotEmpty() && layout.size != rows) fail("layout", "line count must match rows ($rows)")
            layout.forEachIndexed { row, raw ->
                val line = raw as? String ?: fail("layout[$row]", "must be a string")
                if (line.length != 9) fail("layout[$row]", "must contain exactly 9 characters")
                line.forEachIndexed { column, symbol ->
                    if (symbol != ' ') positions.getOrPut(symbol) { ArrayList() }.add(row * 9 + column)
                }
            }
            functions.firstOrNull { it !in positions }?.let { fail("layout", "required functional character '$it' is absent") }
            fun resolve(path: String, value: Any?): MenuEntry {
                val id = value as? String ?: fail(path, "item ID must be a string")
                return try { requireNotNull(resolver.resolve(id)) { "resolver returned null" } }
                catch (failure: RuntimeException) { throw IllegalArgumentException("$sourcePath.$path: cannot resolve item '$id'", failure) }
            }
            val items = LinkedHashMap<Char, MenuEntry>()
            val rawItems = yaml["items"]?.let { it as? Map<*, *> ?: fail("items", "must be a map") } ?: emptyMap<Any, Any>()
            for ((raw, value) in rawItems) {
                val symbol = raw.toString()
                if (symbol.length != 1 || symbol[0] == ' ') fail("items[$symbol]", "key must be one non-space character")
                if (symbol[0] !in positions) fail("items[$symbol]", "character is absent from layout")
                items[symbol[0]] = resolve("items[$symbol]", value)
            }
            positions.forEach { (symbol, slots) ->
                if (symbol !in functions && symbol !in items) {
                    val slot = slots.first()
                    fail("layout[${slot / 9}][${slot % 9}]", "unbound character '$symbol'")
                }
            }
            val staticSlots = LinkedHashMap<Int, MenuEntry>()
            positions.forEach { (symbol, slots) -> if (symbol !in functions) slots.forEach { staticSlots[it] = items.getValue(symbol) } }
            val occupied = positions.values.flatten().toSet()
            val rawSlots = yaml["slots"]?.let { it as? Map<*, *> ?: fail("slots", "must be a map") } ?: emptyMap<Any, Any>()
            for ((key, value) in rawSlots) {
                val slot = key.toString().toIntOrNull() ?: fail("slots[$key]", "key must be an integer")
                if (slot !in 0 until rows * 9) fail("slots[$key]", "outside menu")
                if (slot in occupied || slot in staticSlots) fail("slots[$key]", "slot is already bound")
                staticSlots[slot] = resolve("slots[$key]", value)
            }
            val immutablePositions = positions.mapValues { Collections.unmodifiableList(ArrayList(it.value)) }
            return MenuLayout(title, rows, cancel, sourcePath, immutablePositions, functions, items, staticSlots)
        }
    }
}
