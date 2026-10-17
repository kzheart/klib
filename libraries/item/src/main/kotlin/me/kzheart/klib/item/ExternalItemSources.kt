package me.kzheart.klib.item

import org.bukkit.inventory.ItemStack
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Optional
import java.util.function.BooleanSupplier

/** 反射隔离可选插件；装配时验证 API 形状，运行时保持生成与预览的语义边界。 */
class ExternalItemSources private constructor() {
    private abstract class Source(private val prefix: String, private val plugin: String) : ExternalItemSource {
        override fun prefix(): String = prefix
        override fun plugin(): String = plugin
    }

    private class CraftEngineSource(private val owner: Plugin, private val contentReady: BooleanSupplier) : Source("ce", "CraftEngine") {
        private val api = type(owner.javaClass.classLoader, "net.momirealms.craftengine.bukkit.api.CraftEngineItems")
        private val byId = method(api, "byId", String::class.java)
        private val identify = method(api, "getCustomItemId", ItemStack::class.java)
        private val build = method(byId.returnType, "buildBukkitItem")
        private val buildForPlayer = method(byId.returnType, "buildBukkitItem", Player::class.java)
        init {
            check(Modifier.isStatic(byId.modifiers) && Modifier.isStatic(identify.modifiers)) { "CraftEngineItems entry points must be static" }
            check(ItemStack::class.java.isAssignableFrom(build.returnType) && ItemStack::class.java.isAssignableFrom(buildForPlayer.returnType)) {
                "CraftEngine buildBukkitItem must return ItemStack"
            }
        }
        override fun create(id: String): ItemStack? = create(id, null)
        override fun create(id: String, player: Player?): ItemStack? {
            requireId(id)
            if (!ready()) return null
            val definition = invoke(byId, null, id) ?: return null
            return (if (player == null) invoke(build, definition) else invoke(buildForPlayer, definition, player)) as ItemStack?
        }
        override fun preview(id: String, player: Player?): ItemPreview {
            requireId(id)
            return previewResult {
                if (!ready()) ItemPreview.unavailable(ItemPreview.Status.NOT_READY, "CraftEngine content is not ready or the plugin is disabled")
                else if (invoke(byId, null, id) == null) ItemPreview.unavailable(ItemPreview.Status.UNKNOWN_ID, id)
                else ItemPreview.unavailable(ItemPreview.Status.UNSUPPORTED, "CraftEngine has no safe preview API; buildBukkitItem runs item processors")
            }
        }
        override fun identify(item: ItemStack?): String? = if (InventoryItems.isAir(item)) null else invoke(identify, null, item)?.toString()
        private fun ready(): Boolean = owner.isEnabled && contentReady.asBoolean
        private fun requireId(id: String) { require(id.matches(Regex("[a-z0-9_.-]+:[a-z0-9_./-]+"))) { "CraftEngine id must be namespace:id, got '$id'" } }
    }

    private class ZaphkielSource(private val owner: Plugin) : Source("zap", "ZaphkielPlus") {
        private val items = method(owner.javaClass, "items")
        private val initialService = requireValue(invoke(items, owner), "ZaphkielPlus item service")
        private val generate = method(initialService.javaClass, "generateItemStack", String::class.java, Player::class.java)
        private val preview = optionalMethod(initialService.javaClass, "preview", String::class.java, Player::class.java)
        private val identify = method(initialService.javaClass, "id", ItemStack::class.java)
        private val registry = method(initialService.javaClass, "registry")
        private val find = method(requireValue(invoke(registry, initialService), "ZaphkielPlus definition registry").javaClass, "find", String::class.java)
        override fun create(id: String): ItemStack? = create(id, null)
        override fun create(id: String, player: Player?): ItemStack? {
            if (!owner.isEnabled) return null
            val service = requireValue(invoke(items, owner), "ZaphkielPlus item service")
            val definitions = requireValue(invoke(registry, service), "ZaphkielPlus definition registry")
            if (!find(definitions, id).isPresent) return null
            return invoke(generate, service, id, player) as ItemStack?
        }
        override fun preview(id: String, player: Player?): ItemPreview {
            if (!owner.isEnabled) return ItemPreview.unavailable(ItemPreview.Status.NOT_READY, "ZaphkielPlus is disabled")
            val previewMethod = preview ?: return ItemPreview.unavailable(ItemPreview.Status.UNSUPPORTED, "ZaphkielPlus does not expose preview(String, Player)")
            return previewResult {
                val service = invoke(items, owner) ?: return@previewResult ItemPreview.unavailable(ItemPreview.Status.NOT_READY, "ZaphkielPlus item service is not initialized")
                val definitions = invoke(registry, service) ?: return@previewResult ItemPreview.unavailable(ItemPreview.Status.NOT_READY, "ZaphkielPlus definition registry is not initialized")
                if (!find(definitions, id).isPresent) ItemPreview.unavailable(ItemPreview.Status.UNKNOWN_ID, id)
                else ItemPreview.ready(invoke(previewMethod, service, id, player) as ItemStack)
            }
        }
        private fun find(definitions: Any, id: String): Optional<*> = invoke(find, definitions, id) as? Optional<*>
            ?: throw IllegalStateException("ZaphkielPlus find must return Optional")
        override fun createStack(id: String, amount: Int, player: Player?): ItemStack? {
            require(amount == 1) { "Zap instances must be generated per unit; use ExternalItems.createBatch" }
            return create(id, player)
        }
        override fun identify(item: ItemStack?): String? = invoke(identify, requireValue(invoke(items, owner), "ZaphkielPlus item service"), item) as String?
    }

    private class MmoItemsSource(loader: ClassLoader) : Source("mi", "MMOItems") {
        private val main = type(loader, "net.Indyuce.mmoitems.MMOItems")
        private val plugin = requireValue(staticField(main, "plugin"), "MMOItems.plugin")
        private val getItem = method(main, "getItem", String::class.java, String::class.java)
        private val getType = method(main, "getType", ItemStack::class.java)
        private val getId = method(main, "getID", ItemStack::class.java)
        private val typeId = method(type(loader, "net.Indyuce.mmoitems.api.Type"), "getId")
        override fun create(id: String): ItemStack? {
            val separator = id.indexOf(':')
            require(separator > 0 && separator < id.length - 1) { "MMOItems id must be TYPE:ID, got '$id'" }
            return invoke(getItem, plugin, id.substring(0, separator), id.substring(separator + 1)) as ItemStack?
        }
        override fun identify(item: ItemStack?): String? {
            val type = invoke(getType, null, item) ?: return null
            val id = invoke(getId, null, item) ?: return null
            return "${invoke(typeId, type)}:$id"
        }
    }

    private class NeigeItemsSource(loader: ClassLoader) : Source("ni", "NeigeItems") {
        private val managerType = type(loader, "pers.neige.neigeitems.manager.ItemManager")
        private val getItemStack = method(managerType, "getItemStack", String::class.java)
        private val manager = if (Modifier.isStatic(getItemStack.modifiers)) null else requireValue(staticField(managerType, "INSTANCE"), "ItemManager.INSTANCE")
        private val identify: Method
        private val infoId: Method?
        init {
            val utils = type(loader, "pers.neige.neigeitems.utils.ItemUtils")
            val byId = optionalMethod(utils, "getItemId", ItemStack::class.java)
            if (byId != null && Modifier.isStatic(byId.modifiers)) { identify = byId; infoId = null }
            else { identify = method(utils, "isNiItem", ItemStack::class.java); infoId = method(identify.returnType, "getId") }
        }
        override fun create(id: String): ItemStack? = invoke(getItemStack, manager, id) as ItemStack?
        override fun identify(item: ItemStack?): String? {
            val result = invoke(identify, null, item)
            return (if (result == null || infoId == null) result else invoke(infoId, result)) as String?
        }
    }

    private class ItemsAdderSource(loader: ClassLoader) : Source("ia", "ItemsAdder") {
        private val stack = type(loader, "dev.lone.itemsadder.api.CustomStack")
        private val getInstance = method(stack, "getInstance", String::class.java)
        private val byItemStack = method(stack, "byItemStack", ItemStack::class.java)
        private val getItemStack = method(stack, "getItemStack")
        private val namespacedId = method(stack, "getNamespacedID")
        override fun create(id: String): ItemStack? = invoke(getInstance, null, id)?.let { invoke(getItemStack, it) as ItemStack? }
        override fun identify(item: ItemStack?): String? = invoke(byItemStack, null, item)?.let { invoke(namespacedId, it) as String? }
    }

    private class MythicMobsSource(loader: ClassLoader) : Source("mm", "MythicMobs") {
        private val main = optionalType(loader, "io.lumine.mythic.bukkit.MythicBukkit") ?: type(loader, "io.lumine.xikage.mythicmobs.MythicMobs")
        private val instance = method(main, "inst")
        private val itemManager = method(main, "getItemManager")
        init { method(itemManager.returnType, "getItemStack", String::class.java) }
        override fun create(id: String): ItemStack? {
            val manager = manager()
            return invoke(method(manager.javaClass, "getItemStack", String::class.java), manager, id) as ItemStack?
        }
        override fun identify(item: ItemStack?): String? {
            val manager = manager()
            val type = optionalMethod(manager.javaClass, "getMythicTypeFromItem", ItemStack::class.java) ?: return null
            return invoke(type, manager, item) as String?
        }
        private fun manager(): Any = requireValue(invoke(itemManager, invoke(instance, null)), "MythicMobs item manager")
    }

    companion object {
        @JvmStatic fun zaphkiel(plugin: Plugin): ExternalItemSource = ZaphkielSource(plugin)
        @JvmStatic fun craftEngine(plugin: Plugin, contentReady: BooleanSupplier): ExternalItemSource = CraftEngineSource(plugin, contentReady)
        @JvmStatic fun mmoItems(loader: ClassLoader): ExternalItemSource = MmoItemsSource(loader)
        @JvmStatic fun neigeItems(loader: ClassLoader): ExternalItemSource = NeigeItemsSource(loader)
        @JvmStatic fun itemsAdder(loader: ClassLoader): ExternalItemSource = ItemsAdderSource(loader)
        @JvmStatic fun mythicMobs(loader: ClassLoader): ExternalItemSource = MythicMobsSource(loader)
        private fun type(loader: ClassLoader, name: String): Class<*> = optionalType(loader, name) ?: throw IllegalStateException("Class not found: $name")
        private fun optionalType(loader: ClassLoader, name: String): Class<*>? = try { Class.forName(name, true, loader) } catch (_: ClassNotFoundException) { null }
        private fun method(type: Class<*>, name: String, vararg parameters: Class<*>): Method =
            optionalMethod(type, name, *parameters) ?: throw IllegalStateException("Method not found: ${type.name}#$name")
        private fun optionalMethod(type: Class<*>, name: String, vararg parameters: Class<*>): Method? =
            try { type.getMethod(name, *parameters).apply { isAccessible = true } } catch (_: NoSuchMethodException) { null }
        private fun staticField(type: Class<*>, name: String): Any? = try { type.getField(name).get(null) }
            catch (failure: NoSuchFieldException) { throw IllegalStateException("Field not found: ${type.name}#$name", failure) }
            catch (failure: IllegalAccessException) { throw IllegalStateException("Field not found: ${type.name}#$name", failure) }
        private fun requireValue(value: Any?, description: String): Any = value ?: throw IllegalStateException("$description is not initialized")
        private fun invoke(method: Method, target: Any?, vararg arguments: Any?): Any? = try { method.invoke(target, *arguments) }
            catch (failure: IllegalAccessException) { throw IllegalStateException("Cannot access $method", failure) }
            catch (failure: InvocationTargetException) {
                when (val cause = failure.cause) {
                    is RuntimeException -> throw cause
                    is Error -> throw cause
                    else -> throw IllegalStateException("$method failed", cause)
                }
            }
        private inline fun previewResult(block: () -> ItemPreview): ItemPreview = try { block() }
            catch (failure: RuntimeException) { ItemPreview.failed(failure) }
            catch (failure: LinkageError) { ItemPreview.failed(failure) }
    }
}
