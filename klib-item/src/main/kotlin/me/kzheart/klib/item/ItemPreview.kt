package me.kzheart.klib.item

import java.util.Optional
import org.bukkit.inventory.ItemStack

/** 无交付语义的展示结果；成功物品始终按防御性副本返回。 */
class ItemPreview private constructor(private val status: Status, private val item: ItemStack?,
                                     private val detail: String, private val failure: Throwable?) {
    enum class Status { READY, UNSUPPORTED, NOT_READY, UNKNOWN_ID, FAILED }
    fun status(): Status = status
    fun item(): Optional<ItemStack> = Optional.ofNullable(item?.clone())
    fun detail(): String = detail
    fun failure(): Optional<Throwable> = Optional.ofNullable(failure)
    companion object {
        @JvmStatic fun ready(item: ItemStack?): ItemPreview {
            require(!InventoryItems.isAir(item)) { "Preview item must not be empty" }
            return ItemPreview(Status.READY, item!!.clone(), "", null)
        }
        @JvmStatic fun unavailable(status: Status, detail: String): ItemPreview {
            require(status != Status.READY && status != Status.FAILED) { "Use ready or failed for this status" }
            return ItemPreview(status, null, detail, null)
        }
        @JvmStatic fun failed(failure: Throwable): ItemPreview = ItemPreview(Status.FAILED, null, failure.toString(), failure)
    }
}
