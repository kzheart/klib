package me.kzheart.klib.item

import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import java.util.Collections
import java.util.Objects
import java.util.function.Predicate
import java.util.regex.Pattern

/** 配置和钩子模块共用、仅解析一次的物品匹配器与生成器。 */
class ItemSpec private constructor(builder: Builder) {
    private val material = builder.material
    private val amount = builder.amount
    private val generatedName = builder.generatedName
    private val namePattern = builder.namePattern
    private val lorePatterns = builder.lorePatterns.toList()
    private val tags = LinkedHashMap(builder.tags)
    private val predicate = builder.predicate
    private val externalProvider = builder.externalProvider
    private val externalId = builder.externalId
    private val externals = builder.externals
    init { check(material != null || externalId != null) { "ItemSpec needs a material or external item id" } }

    fun matches(item: ItemStack?): Boolean {
        if (InventoryItems.isAir(item)) return false
        val candidate = item!!
        if (material != null && candidate.type != material) return false
        if (externalId != null && !externals!!.matches(externalProvider, externalId, candidate)) return false
        val meta = candidate.itemMeta
        if (namePattern != null && (meta == null || !meta.hasDisplayName() || !namePattern.matcher(meta.displayName).matches())) return false
        val lore = if (meta != null && meta.hasLore()) meta.lore.orEmpty() else emptyList()
        if (lorePatterns.any { pattern -> lore.none { pattern.matcher(it).matches() } }) return false
        if (tags.any { (key, expected) -> !key.valueType().valuesEqual(key.get(candidate), expected) }) return false
        return predicate.test(candidate)
    }

    fun create(): ItemStack {
        val item = if (externalId == null) ItemStack(material!!, amount ?: 1)
            else Objects.requireNonNull(externals!!.create(externalProvider, externalId), "external item").clone()
        amount?.let { item.amount = it }
        val builder = Items.edit(item)
        generatedName?.let { builder.name(it) }
        val result = builder.build()
        for ((key, value) in tags) {
            @Suppress("UNCHECKED_CAST")
            (key as TagKey<Any>).set(result, value)
        }
        return result
    }

    class Builder {
        internal var material: Material? = null
        internal var amount: Int? = null
        internal var generatedName: String? = null
        internal var namePattern: Pattern? = null
        internal val lorePatterns = ArrayList<Pattern>()
        internal val tags = LinkedHashMap<TagKey<*>, Any>()
        internal var predicate = Predicate<ItemStack> { true }
        internal var externalProvider: String? = null
        internal var externalId: String? = null
        internal var externals: ExternalItemProvider? = null
        fun material(value: Material): Builder = apply { material = value }
        fun material(value: String): Builder = material(Items.resolveMaterial(value))
        fun amount(value: Int): Builder = apply {
            require(value in 1..64) { "Amount must be between 1 and 64" }; amount = value
        }
        fun name(value: String): Builder = apply { generatedName = value }
        fun nameRegex(expression: String): Builder = apply { namePattern = Pattern.compile(expression) }
        fun loreRegex(expression: String): Builder = apply { lorePatterns.add(Pattern.compile(expression)) }
        fun <T> tag(key: TagKey<T>, value: T): Builder = apply { tags[key] = Objects.requireNonNull(value, "value") as Any }
        fun matching(value: Predicate<ItemStack>): Builder = apply { predicate = predicate.and(value) }
        fun external(provider: String, id: String, adapter: ExternalItemProvider): Builder = apply {
            externalProvider = provider; externalId = id; externals = adapter
        }
        fun build(): ItemSpec = ItemSpec(this)
    }

    companion object {
        @JvmStatic fun builder(): Builder = Builder()
        @JvmStatic fun from(values: Map<String, *>): ItemSpec {
            val builder = builder()
            values["material"]?.let { builder.material(it.toString()) }
            when (val amount = values["amount"]) {
                null -> Unit
                is Number -> builder.amount(amount.toInt())
                else -> throw IllegalArgumentException("amount must be a number, got: $amount")
            }
            values["name"]?.let { builder.name(it.toString()) }
            values["name-regex"]?.let { builder.nameRegex(it.toString()) }
            (values["lore-regex"] as? Iterable<*>)?.forEach { builder.loreRegex(it.toString()) }
            (values["tags"] as? Map<*, *>)?.forEach { (key, value) -> builder.tag(TagKey.string(key.toString()), value.toString()) }
            return builder.build()
        }
    }
}
