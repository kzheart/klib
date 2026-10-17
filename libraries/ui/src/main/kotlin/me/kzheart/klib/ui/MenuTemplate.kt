package me.kzheart.klib.ui

import java.util.Collections

/** MenuCompiler 接受的源模板。 */
class MenuTemplate private constructor(builder: Builder) {
    private val title = builder.title
    private val rows = builder.rows
    private val cancelClicks = builder.cancelClicks
    private val layout = Collections.unmodifiableList(ArrayList(builder.layout))
    private val characters = Collections.unmodifiableMap(LinkedHashMap(builder.characters))
    private val slots = Collections.unmodifiableMap(LinkedHashMap(builder.slots))
    @JvmName("title") internal fun title(): String = title
    @JvmName("rows") internal fun rows(): Int = rows
    @JvmName("cancelClicks") internal fun cancelClicks(): Boolean = cancelClicks
    @JvmName("layout") internal fun layout(): List<String> = layout
    @JvmName("characters") internal fun characters(): Map<Char, MenuEntry> = characters
    @JvmName("slots") internal fun slots(): Map<Int, MenuEntry> = slots
    class Builder internal constructor(internal val title: String, internal val rows: Int) {
        internal var cancelClicks = true
        internal val layout = ArrayList<String>()
        internal val characters = LinkedHashMap<Char, MenuEntry>()
        internal val slots = LinkedHashMap<Int, MenuEntry>()
        init { require(rows in 1..6) { "rows must be between 1 and 6" } }
        fun cancelClicks(value: Boolean): Builder = apply { cancelClicks = value }
        fun layout(vararg lines: String): Builder = apply { layout.clear(); lines.forEach { layout.add(java.util.Objects.requireNonNull(it, "layout line")) } }
        fun character(symbol: Char, entry: MenuEntry): Builder = apply {
            require(symbol != ' ') { "space is reserved for an empty slot" }
            require(characters.put(symbol, entry) == null) { "duplicate layout character: $symbol" }
        }
        fun slot(slot: Int, entry: MenuEntry): Builder = apply { require(slots.put(slot, entry) == null) { "duplicate slot: $slot" } }
        fun build(): MenuTemplate = MenuTemplate(this)
    }
    companion object { @JvmStatic fun builder(title: String, rows: Int): Builder = Builder(title, rows) }
}
