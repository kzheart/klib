package me.kzheart.klib.item

import java.util.Collections
import java.util.Locale
import java.util.Optional
import java.util.function.Predicate
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.PluginManager

/** 不可变的外部物品注册表；物品生成与识别遵守对应插件的线程要求。 */
class ExternalItems private constructor(sources: Map<String, ExternalItemSource>, states: Map<String, SourceState>) : ExternalItemProvider {
    private val sources = Collections.unmodifiableMap(LinkedHashMap(sources))
    private val states = Collections.unmodifiableMap(LinkedHashMap(states))

    fun with(vararg additions: ExternalItemSource): ExternalItems {
        val nextSources = LinkedHashMap(sources)
        val nextStates = LinkedHashMap(states)
        for (source in additions) {
            val prefix = ItemRef.requirePrefix(source.prefix())
            require(prefix != ItemRef.VANILLA) { "Prefix 'minecraft' is reserved for vanilla materials" }
            nextSources[prefix] = source
            nextStates[prefix] = SourceState(prefix, source.plugin(), Status.AVAILABLE, "已注册")
        }
        return ExternalItems(nextSources, nextStates)
    }
    fun report(): List<SourceState> = Collections.unmodifiableList(ArrayList(states.values))
    fun available(prefix: String): Boolean = sources.containsKey(prefix)
    fun parse(reference: String): ItemRef {
        val text = reference.trim()
        val separator = text.indexOf(':')
        if (separator > 0) {
            val prefix = text.substring(0, separator).lowercase(Locale.ROOT)
            if (prefix in sources || prefix in states || prefix in BUILTINS) return ItemRef.of(prefix, text.substring(separator + 1))
            require(prefix == ItemRef.VANILLA) { "Unknown item source '$prefix' in '$reference', known sources: ${knownPrefixes()}" }
        }
        return ItemRef.of(ItemRef.VANILLA, Items.resolveMaterial(text).name)
    }
    fun create(reference: String): Optional<ItemStack> = create(parse(reference))
    fun preview(reference: String, player: Player?): ItemPreview {
        val ref = parse(reference)
        return try {
            if (ref.vanilla()) return ItemPreview.ready(ItemStack(Items.resolveMaterial(ref.id())))
            val source = sources[ref.prefix()]
            if (source == null) {
                val state = states[ref.prefix()]
                if (state?.status() == Status.FAILED) ItemPreview.failed(IllegalStateException(state.detail()))
                else ItemPreview.unavailable(ItemPreview.Status.NOT_READY, "${ref.prefix()} is unavailable")
            } else java.util.Objects.requireNonNull(source.preview(ref.id(), player), "source preview result")
        } catch (failure: RuntimeException) { ItemPreview.failed(failure) }
        catch (failure: LinkageError) { ItemPreview.failed(failure) }
    }
    fun create(reference: String, amount: Int): Optional<ItemStack> = create(reference, amount, null)
    fun create(reference: String, amount: Int, player: Player?): Optional<ItemStack> {
        require(amount > 0) { "Amount must be positive" }
        val ref = parse(reference)
        if (ref.vanilla()) return Optional.of(ItemStack(Items.resolveMaterial(ref.id()), amount))
        val source = sources[ref.prefix()] ?: return Optional.empty()
        return snapshot(source.createStack(ref.id(), amount, player))
    }
    fun createBatch(reference: String, amount: Int, player: Player?): List<ItemStack> {
        require(amount > 0) { "Amount must be positive" }
        val ref = parse(reference)
        val result = ArrayList<ItemStack>()
        repeat(amount) {
            val item = if (ref.vanilla()) ItemStack(Items.resolveMaterial(ref.id()))
                else (sources[ref.prefix()] ?: return emptyList()).create(ref.id(), player)
            if (InventoryItems.isAir(item)) return emptyList()
            result.add(item!!.clone().apply { this.amount = 1 })
        }
        return Collections.unmodifiableList(result)
    }
    fun create(reference: ItemRef): Optional<ItemStack> {
        if (reference.vanilla()) return Optional.of(ItemStack(Items.resolveMaterial(reference.id())))
        val source = sources[reference.prefix()] ?: return Optional.empty()
        return snapshot(source.create(reference.id()))
    }
    fun identify(item: ItemStack?): Optional<ItemRef> {
        if (InventoryItems.isAir(item)) return Optional.empty()
        for (source in sources.values) source.identify(item)?.let { return Optional.of(ItemRef.of(source.prefix(), it)) }
        return Optional.empty()
    }
    fun matches(reference: String, item: ItemStack?): Boolean = matcher(reference).test(item)
    fun matcher(reference: String): Predicate<ItemStack?> {
        val ref = parse(reference)
        if (ref.vanilla()) {
            val material = Items.resolveMaterial(ref.id())
            return Predicate { !InventoryItems.isAir(it) && it!!.type == material && !identify(it).isPresent }
        }
        return Predicate { item -> !InventoryItems.isAir(item) && sources[ref.prefix()]?.let { it.identify(item) == ref.id() } == true }
    }
    fun spec(reference: String): ItemSpec {
        val ref = parse(reference)
        val matching = matcher(reference)
        return if (ref.vanilla()) ItemSpec.builder().material(ref.id()).matching(Predicate { matching.test(it) }).build()
            else ItemSpec.builder().external(ref.prefix(), ref.id(), this).build()
    }
    override fun matches(provider: String, id: String, item: ItemStack?): Boolean =
        !InventoryItems.isAir(item) && sources[provider]?.let { it.identify(item) == id } == true
    override fun create(provider: String, id: String): ItemStack = create(ItemRef.of(provider, id)).orElseThrow {
        IllegalStateException("External item is unavailable: $provider:$id")
    }
    private fun knownPrefixes(): String = (sources.keys + BUILTINS.keys + ItemRef.VANILLA).joinToString(", ")
    private fun snapshot(item: ItemStack?): Optional<ItemStack> = if (InventoryItems.isAir(item)) Optional.empty() else Optional.of(item!!.clone())

    enum class Status { AVAILABLE, MISSING, FAILED }
    class SourceState internal constructor(private val prefix: String, private val plugin: String,
                                          private val status: Status, private val detail: String) {
        fun prefix(): String = prefix
        fun plugin(): String = plugin
        fun status(): Status = status
        fun detail(): String = detail
        override fun toString(): String = "$prefix($plugin)=$status" + if (status == Status.AVAILABLE) "" else ": $detail"
    }
    private class Builtin(val plugin: String, val create: (Plugin) -> ExternalItemSource)
    companion object {
        private val BUILTINS = linkedMapOf(
            "zap" to Builtin("ZaphkielPlus", ExternalItemSources::zaphkiel),
            "mi" to Builtin("MMOItems") { ExternalItemSources.mmoItems(it.javaClass.classLoader) },
            "ni" to Builtin("NeigeItems") { ExternalItemSources.neigeItems(it.javaClass.classLoader) },
            "ia" to Builtin("ItemsAdder") { ExternalItemSources.itemsAdder(it.javaClass.classLoader) },
            "mm" to Builtin("MythicMobs") { ExternalItemSources.mythicMobs(it.javaClass.classLoader) })
        @JvmStatic fun detect(): ExternalItems = detect(Bukkit.getPluginManager())
        @JvmStatic fun detect(plugins: PluginManager): ExternalItems {
            val sources = LinkedHashMap<String, ExternalItemSource>()
            val states = LinkedHashMap<String, SourceState>()
            for ((prefix, builtin) in BUILTINS) {
                val plugin = plugins.getPlugin(builtin.plugin)
                if (plugin == null || !plugin.isEnabled) {
                    states[prefix] = SourceState(prefix, builtin.plugin, Status.MISSING, "未安装或未启用")
                    continue
                }
                try {
                    sources[prefix] = builtin.create(plugin)
                    states[prefix] = SourceState(prefix, builtin.plugin, Status.AVAILABLE, "已挂钩")
                } catch (failure: RuntimeException) { states[prefix] = failed(prefix, builtin.plugin, failure) }
                catch (failure: LinkageError) { states[prefix] = failed(prefix, builtin.plugin, failure) }
            }
            return ExternalItems(sources, states)
        }
        private fun failed(prefix: String, plugin: String, failure: Throwable): SourceState = SourceState(prefix, plugin,
            Status.FAILED, failure.javaClass.name + (failure.message?.takeIf { it.isNotEmpty() }?.let { ": $it" } ?: ""))
        @JvmStatic fun of(vararg sources: ExternalItemSource): ExternalItems = empty().with(*sources)
        @JvmStatic fun empty(): ExternalItems = ExternalItems(emptyMap(), emptyMap())
    }
}
