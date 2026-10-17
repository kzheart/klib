package me.kzheart.klib.lang

import java.lang.reflect.InvocationTargetException
import java.util.Collections
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BookMeta
import org.bukkit.inventory.meta.ItemMeta

/** Host-owned Adventure stays opaque across the shaded classloader boundary. */
class BukkitAdventure private constructor() {
    companion object {
        private fun type(suffix: String): Class<*> = try {
            Bukkit::class.java.classLoader.loadClass(listOf("net", "kyori", "adventure", suffix).joinToString("."))
        } catch (failure: ClassNotFoundException) {
            throw IllegalStateException("The host does not provide Adventure: $suffix", failure)
        }
        @JvmStatic fun available(): Boolean = try { type("text.minimessage.MiniMessage"); true }
            catch (_: IllegalStateException) { false }

        private fun invoke(owner: Class<*>, receiver: Any?, name: String, signature: Array<Class<*>>, vararg args: Any?): Any? {
            try {
                val selected = try { owner.getMethod(name, *signature) } catch (_: NoSuchMethodException) {
                    owner.methods.firstOrNull { method -> method.name == name && method.parameterTypes.size == args.size &&
                        method.parameterTypes.indices.all { args[it] == null || method.parameterTypes[it].isInstance(args[it]) } }
                        ?: throw NoSuchMethodException("${owner.name}.$name")
                }
                return selected.invoke(receiver, *args)
            } catch (failure: InvocationTargetException) {
                throw IllegalStateException("Host Adventure call failed: $name", failure.cause)
            } catch (failure: ReflectiveOperationException) {
                throw IllegalStateException("Host Adventure method unavailable: $name", failure)
            }
        }

        @JvmStatic fun parse(text: String): Any {
            if ('§' in text || text.matches(Regex("(?s).*&[0-9a-fk-orA-FK-OR].*"))) {
                val legacy = type("text.serializer.legacy.LegacyComponentSerializer")
                val serializer = invoke(legacy, null, "legacySection", emptyArray())
                return invoke(legacy, serializer, "deserialize", arrayOf(String::class.java), ChatColor.translateAlternateColorCodes('&', text))!!
            }
            val mini = type("text.minimessage.MiniMessage")
            return invoke(mini, invoke(mini, null, "miniMessage", emptyArray()), "deserialize", arrayOf(String::class.java), text)!!
        }
        @JvmStatic fun miniMessage(component: Any): String = serialize("text.minimessage.MiniMessage", "miniMessage", component)
        @JvmStatic fun plain(component: Any): String = serialize("text.serializer.plain.PlainTextComponentSerializer", "plainText", component)
        @JvmStatic fun legacy(text: String): String = serialize("text.serializer.legacy.LegacyComponentSerializer", "legacySection", parse(text))
        private fun serialize(suffix: String, factory: String, component: Any): String {
            val serializer = type(suffix)
            return invoke(serializer, invoke(serializer, null, factory, emptyArray()), "serialize", arrayOf(type("text.Component")), component) as String
        }
        @JvmStatic fun actionbar(player: Player, text: String) {
            mainThread(); invoke(Player::class.java, player, "sendActionBar", arrayOf(type("text.Component")), parse(text))
        }
        @JvmStatic fun displayName(item: ItemStack): Any {
            mainThread()
            val meta = item.itemMeta
            return if (meta != null && meta.hasDisplayName()) invoke(ItemMeta::class.java, meta, "displayName", emptyArray())!!
                else parse(item.type.name)
        }
        @JvmStatic fun lore(meta: ItemMeta): List<Any> {
            mainThread()
            val value = invoke(ItemMeta::class.java, meta, "lore", emptyArray()) as? List<*> ?: return emptyList()
            @Suppress("UNCHECKED_CAST")
            return Collections.unmodifiableList(ArrayList(value)) as List<Any>
        }
        @JvmStatic fun inventory(holder: InventoryHolder?, size: Int, title: String): Inventory {
            mainThread()
            return invoke(Bukkit::class.java, null, "createInventory", arrayOf(InventoryHolder::class.java, Int::class.javaPrimitiveType!!,
                type("text.Component")), holder, size, parse(title)) as Inventory
        }
        @JvmStatic fun book(player: Player, title: String, author: String, pages: List<String>) {
            mainThread()
            val item = ItemStack(Material.WRITTEN_BOOK)
            val meta = item.itemMeta as BookMeta
            meta.title = title
            meta.author = author
            invoke(BookMeta::class.java, meta, "pages", arrayOf(List::class.java), pages.map(::parse))
            item.itemMeta = meta
            invoke(Player::class.java, player, "openBook", arrayOf(ItemStack::class.java), item)
        }
        private fun mainThread() { check(Bukkit.isPrimaryThread()) { "Paper Adventure operation requires the main thread" } }
    }
}
