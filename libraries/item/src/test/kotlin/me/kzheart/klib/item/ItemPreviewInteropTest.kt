package me.kzheart.klib.item

import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean
import net.momirealms.craftengine.bukkit.api.CraftEngineItems
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ItemPreviewInteropTest {
    @Test fun `Kotlin consumes the same safe preview result without generation`() {
        var generated = 0
        val source = object : ExternalItemSource {
            override fun prefix() = "custom"
            override fun plugin() = "Custom"
            override fun create(id: String): ItemStack {
                generated++
                return ItemStack(Material.DIAMOND)
            }
            override fun identify(item: ItemStack): String? = null
        }
        val preview: ItemPreview = ExternalItems.of(source).preview("custom:gem", null)
        assertEquals(ItemPreview.Status.UNSUPPORTED, preview.status())
        assertFalse(preview.item().isPresent)
        assertEquals(0, generated)
    }

    @Test fun `Kotlin supplies explicit content readiness without a second adapter`() {
        CraftEngineItems.reset()
        val ready = AtomicBoolean(false)
        val plugin = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Plugin::class.java)) { _, method, _ ->
            if (method.name == "isEnabled") true else null
        } as Plugin
        val items = ExternalItems.of(ExternalItemSources.craftEngine(plugin, ready::get))
        assertEquals(ItemPreview.Status.NOT_READY, items.preview("ce:example:gem", null).status())
        ready.set(true)
        assertEquals(ItemPreview.Status.UNKNOWN_ID, items.preview("ce:example:gem", null).status())
        assertEquals(0, CraftEngineItems.registryReads)
    }
}
