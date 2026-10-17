package me.kzheart.klib.ui

/** 将所有受支持的菜单声明编译为一个不可变模型。 */
class MenuCompiler private constructor() {
    companion object {
        @JvmStatic fun compile(template: MenuTemplate): MenuModel {
            val entries = LinkedHashMap<Int, MenuEntry>()
            val layout = template.layout()
            require(layout.isEmpty() || layout.size == template.rows()) { "layout line count must match menu rows" }
            for ((row, line) in layout.withIndex()) {
                require(line.length == 9) { "layout rows must contain exactly 9 characters" }
                for ((column, symbol) in line.withIndex()) {
                    if (symbol == ' ') continue
                    entries[row * 9 + column] = template.characters()[symbol]
                        ?: throw IllegalArgumentException("unbound layout character: $symbol")
                }
            }
            for ((slot, entry) in template.slots()) {
                require(slot !in entries) { "slot $slot is already bound by the layout" }
                entries[slot] = entry
            }
            return MenuModel(template.title(), template.rows(), template.cancelClicks(), entries)
        }
        @JvmStatic fun compileSlots(title: String, rows: Int, slots: Map<Int, MenuEntry>): MenuModel {
            val builder = MenuTemplate.builder(title, rows)
            slots.forEach { (slot, entry) -> builder.slot(slot, entry) }
            return compile(builder.build())
        }
        @JvmStatic fun compileYaml(yaml: Map<String, *>, resolver: YamlItemResolver): MenuModel {
            val builder = MenuTemplate.builder(yaml["title"]?.toString() ?: "Menu", yaml["rows"]?.toString()?.toInt() ?: 1)
                .cancelClicks(bool(yaml["cancel-clicks"], "cancel-clicks", true))
            yaml["layout"]?.let { layout ->
                require(layout is List<*>) { "layout must be a list" }
                builder.layout(*layout.map { it.toString() }.toTypedArray())
            }
            yaml["items"]?.let { items ->
                require(items is Map<*, *>) { "items must be a map" }
                for ((key, value) in items) {
                    val symbol = key.toString()
                    require(symbol.length == 1) { "item character must have length 1: $symbol" }
                    builder.character(symbol[0], requireResolved(resolver, value))
                }
            }
            yaml["slots"]?.let { slots ->
                require(slots is Map<*, *>) { "slots must be a map" }
                slots.forEach { (key, value) -> builder.slot(key.toString().toInt(), requireResolved(resolver, value)) }
            }
            return compile(builder.build())
        }
        private fun requireResolved(resolver: YamlItemResolver, id: Any?): MenuEntry =
            java.util.Objects.requireNonNull(resolver.resolve(id.toString()), "resolver returned null for $id")
        private fun bool(value: Any?, key: String, fallback: Boolean): Boolean = when {
            value == null -> fallback
            value is Boolean -> value
            value.toString().trim().equals("true", true) -> true
            value.toString().trim().equals("false", true) -> false
            else -> throw IllegalArgumentException("$key must be true or false, got: $value")
        }
    }
}
