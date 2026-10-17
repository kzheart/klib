package me.kzheart.klib.ui

/** 单次编译的动态绑定；功能字符必须且只能处理一次。 */
class MenuBindings internal constructor(
    private val sourcePath: String,
    private val positions: Map<Char, List<Int>>,
    private val functions: Set<Char>,
    private val items: Map<Char, MenuEntry>,
    staticSlots: Map<Int, MenuEntry>
) {
    private val entries = LinkedHashMap(staticSlots)
    private val bound = HashSet<Char>()
    private var closed = false

    fun character(symbol: Char, entry: MenuEntry) {
        bind(symbol).forEach { entries[it] = entry }
    }
    fun action(symbol: Char, action: MenuAction) {
        val configured = items[symbol] ?: fail(symbol, "action requires a configured item")
        var entry = MenuEntry.of(configured.item(), action)
        configured.clickSound().ifPresent { entry = entry.withClickSound(it) }
        character(symbol, entry)
    }
    fun entries(symbol: Char, values: List<MenuEntry>) {
        val slots = bind(symbol)
        if (values.size > slots.size) fail(symbol, "${values.size} entries exceed layout capacity ${slots.size}")
        values.forEachIndexed { index, entry ->
            entries[slots[index]] = requireNotNull(entry) { "$sourcePath.bindings[$symbol][$index]: entry must not be null" }
        }
    }
    fun hide(symbol: Char) { bind(symbol) }
    fun reserve(symbol: Char) { bind(symbol) }

    private fun bind(symbol: Char): List<Int> {
        check(!closed) { "$sourcePath.bindings: compilation has ended" }
        if (symbol !in functions) fail(symbol, "not declared as a functional character")
        if (!bound.add(symbol)) fail(symbol, "character is already bound")
        return positions.getValue(symbol)
    }
    private fun fail(symbol: Char, message: String): Nothing =
        throw IllegalArgumentException("$sourcePath.bindings[$symbol]: $message")
    internal fun finish(): Map<Int, MenuEntry> {
        functions.firstOrNull { it !in bound }?.let { fail(it, "missing binding; use hide or reserve for an empty region") }
        return entries
    }
    internal fun close() { closed = true }
}
