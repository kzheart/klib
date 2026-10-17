package me.kzheart.klib.item.tag

import me.kzheart.klib.item.InventoryItems
import org.bukkit.Bukkit
import org.bukkit.inventory.ItemStack
import java.io.ByteArrayInputStream
import java.io.DataInput
import java.io.DataInputStream
import java.io.DataOutput
import java.io.DataOutputStream
import java.io.IOException
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType

/** 当前服务端真正读写的存储位置，二者不代表相同的原版物品结构。 */
enum class ItemTagStorage { LEGACY_ROOT, CUSTOM_DATA }

/**
 * 原生标签的一个小而完整的边界：读取独立快照，编辑后写回同一物品。
 * 旧服务端读写根 NBT；组件服务端仅读写 minecraft:custom_data，保留其他组件。
 * 不提供完整组件编辑或跨存储格式转换；应在 Bukkit 主线程调用。
 */
object NativeItemTags {
    @Volatile private var cached: Access? = null
    @Volatile private var serverClass: Class<*>? = null
    @Volatile private var failure: Throwable? = null

    @JvmStatic fun supports(): Boolean = accessOrNull() != null
    @JvmStatic fun storage(): ItemTagStorage = access().storage

    @JvmStatic fun read(item: ItemStack): NbtCompound {
        if (InventoryItems.isAir(item)) return NbtCompound()
        return operation { access().read(item) }
    }

    /**
     * 完整替换 [storage] 对应的槽位。保留已有标签/PDC 时，应先 [read] 再编辑该快照。
     * 写入空复合值会清空此槽位；组件服务端同时移除空 custom_data 组件。
     */
    @JvmStatic fun write(item: ItemStack, tags: NbtCompound) {
        require(!InventoryItems.isAir(item)) { "Cannot write NBT to an air item" }
        operation { access().write(item, tags) }
    }

    private fun access(): Access = accessOrNull()
        ?: throw IllegalStateException("Native item NBT is unavailable for this server layout", failure)

    private fun accessOrNull(): Access? {
        val server = Bukkit.getServer() ?: return null // 启动前探测不永久缓存失败。
        if (serverClass == server.javaClass) return cached
        synchronized(this) {
            if (serverClass != server.javaClass) {
                try {
                    cached = Access(server.javaClass)
                    failure = null
                } catch (error: ReflectiveOperationException) {
                    cached = null; failure = error
                } catch (error: LinkageError) {
                    cached = null; failure = error
                } catch (error: UnsupportedOperationException) {
                    cached = null; failure = error
                } catch (error: IOException) {
                    cached = null; failure = error
                }
                serverClass = server.javaClass
            }
        }
        return cached
    }

    private inline fun <T> operation(block: () -> T): T = try { block() } catch (error: InvocationTargetException) {
        throw IllegalStateException("Native item NBT operation failed", error.cause)
    } catch (error: ReflectiveOperationException) {
        throw IllegalStateException("Native item NBT operation failed", error)
    } catch (error: IOException) {
        throw IllegalStateException("Native item NBT encoding failed", error)
    }

    /** 只解析一次的服务端边界；按精确签名/唯一字段类型校验布局，不猜测混淆字段名。 */
    private class Access(server: Class<*>) {
        private val loader = server.classLoader
        private val serverPackage = server.`package`?.name
            ?: throw UnsupportedOperationException("Native item NBT requires a named Craft server package")
        private val craft = Class.forName("$serverPackage.inventory.CraftItemStack", false, loader)
        private val toNative = craft.getMethod("asNMSCopy", ItemStack::class.java)
        private val itemType = toNative.returnType
        private val toBukkit = try { craft.getMethod("asBukkitCopy", itemType) } catch (_: NoSuchMethodException) {
            // 新布局移除了 asBukkitCopy；asNMSCopy 已保证待镜像的 handle 是独立副本。
            craft.getMethod("asCraftMirror", itemType)
        }
        private val compound = load("net.minecraft.nbt.CompoundTag", "net.minecraft.nbt.NBTTagCompound", "${itemType.`package`.name}.NBTTagCompound")
        private val io = load("net.minecraft.nbt.NbtIo", "net.minecraft.nbt.NBTCompressedStreamTools", "${itemType.`package`.name}.NBTCompressedStreamTools")
        private val writeNbt = outputMethod()
        private val readNbt = unique(io.methods.filter { Modifier.isStatic(it.modifiers) && it.returnType == compound && it.parameterTypes.size == 2 && it.parameterTypes[0] == DataInput::class.java }, "NBT input")
        private val limiterType = readNbt.parameterTypes[1]
        private val limiter: () -> Any = limiterFactory()
        private val rootField = itemType.declaredFields.filter { !Modifier.isStatic(it.modifiers) && it.type == compound }
        val storage = if (rootField.size == 1) ItemTagStorage.LEGACY_ROOT else ItemTagStorage.CUSTOM_DATA
        private val root: Field? = if (storage == ItemTagStorage.LEGACY_ROOT) rootField.single().accessible() else null
        private val component: Any?
        private val getComponent: Method?
        private val setComponent: Method?
        private val customTag: Field?

        init {
            if (storage == ItemTagStorage.LEGACY_ROOT) {
                requireLayout(!Modifier.isFinal(root!!.modifiers), "Mutable legacy root")
                component = null; getComponent = null; setComponent = null; customTag = null
            } else {
                // 此分支必须完整解析组件 API；绝不把找不到旧字段当成一个空标签。
                requireLayout(rootField.isEmpty(), "Unambiguous legacy root")
                val componentType = load("net.minecraft.core.component.DataComponentType")
                val customType = load("net.minecraft.world.item.component.CustomData")
                val components = load("net.minecraft.core.component.DataComponents")
                val fields = components.fields.filter { Modifier.isStatic(it.modifiers) && it.type == componentType }
                val customField = fields.firstOrNull { it.name == "CUSTOM_DATA" } ?: unique(fields.filter {
                    (it.genericType as? ParameterizedType)?.actualTypeArguments?.singleOrNull() == customType
                }, "CUSTOM_DATA component")
                component = customField.get(null)
                val holder = load("net.minecraft.core.component.DataComponentHolder")
                getComponent = unique(holder.methods.filter { it.parameterTypes.contentEquals(arrayOf(componentType)) && it.returnType == Any::class.java }, "Component getter")
                // CustomData.set 同时负责空 compound 时移除组件，避免遗留影响堆叠的空组件。
                setComponent = unique(customType.methods.filter { Modifier.isStatic(it.modifiers) && it.parameterTypes.contentEquals(arrayOf(componentType, itemType, compound)) && it.returnType == Void.TYPE }, "CustomData setter")
                customTag = unique(customType.declaredFields.filter { !Modifier.isStatic(it.modifiers) && it.type == compound }, "CustomData compound").accessible()
            }
            // 用真正的服务端编解码器验证线格式与反射签名，能力探测不接触物品。
            val sample = NbtCompound().put("klib_probe", NbtTag.intValue(7))
            requireLayout(decode(encode(sample)) == sample, "NBT binary round trip")
        }

        fun read(item: ItemStack): NbtCompound {
            val handle = toNative.invoke(null, item)
            val tag = if (root != null) root.get(handle) else {
                getComponent!!.invoke(handle, component)?.let { customTag!!.get(it) }
            }
            return if (tag == null) NbtCompound() else decode(tag)
        }

        fun write(item: ItemStack, tags: NbtCompound) {
            val tag = encode(tags) // 完整校验/编码成功后才修改副本。
            val handle = toNative.invoke(null, item)
            if (root != null) root.set(handle, tag)
            else setComponent!!.invoke(null, component, handle, tag)
            val replacement = toBukkit.invoke(null, handle) as ItemStack
            check(item.setItemMeta(replacement.itemMeta)) { "Bukkit rejected native item tag metadata" }
        }

        private fun encode(tags: NbtCompound): Any = DataInputStream(ByteArrayInputStream(NbtBinary.encode(tags))).use {
            readNbt.invoke(null, it, limiter())
        }

        private fun decode(tag: Any): NbtCompound {
            val bytes = NbtBinary.LimitedOutput()
            DataOutputStream(bytes).use { writeNbt.invoke(null, tag, it) }
            return NbtBinary.decode(bytes.toByteArray())
        }

        private fun outputMethod(): Method {
            val methods = io.methods.filter { Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE && it.parameterTypes.contentEquals(arrayOf(compound, DataOutput::class.java)) }
            // Paper 旧布局同时公开 writeNBT 与混淆入口 a；二者签名相同。
            return methods.firstOrNull { it.name == "write" }
                ?: methods.firstOrNull { it.name == "writeNBT" }
                ?: unique(methods, "NBT output")
        }

        private fun limiterFactory(): () -> Any {
            val factory = limiterType.methods.filter { Modifier.isStatic(it.modifiers) && it.returnType == limiterType && it.parameterTypes.isEmpty() }
            // 新服务端还提供 defaultQuota / uncompressedQuota，不能仅凭返回类型选择。
            factory.firstOrNull { it.name == "unlimitedHeap" }?.let { return { it.invoke(null) } }
            if (factory.size == 1) return { factory.single().invoke(null) }
            val singleton = limiterType.fields.filter { Modifier.isStatic(it.modifiers) && it.type == limiterType }
            if (singleton.size == 1) return { singleton.single().get(null) }
            throw UnsupportedOperationException("Cannot resolve the NBT read limiter")
        }

        private fun load(vararg names: String): Class<*> {
            names.forEach { try { return Class.forName(it, false, loader) } catch (_: ClassNotFoundException) { } }
            throw ClassNotFoundException(names.joinToString(" or "))
        }
        private fun Field.accessible(): Field = apply { isAccessible = true }
        private fun <T> unique(values: List<T>, operation: String): T {
            requireLayout(values.size == 1, "$operation (found ${values.size} candidates)")
            return values.single()
        }
        private fun requireLayout(condition: Boolean, operation: String) {
            if (!condition) throw UnsupportedOperationException("Unsupported native item layout: $operation")
        }
    }
}
