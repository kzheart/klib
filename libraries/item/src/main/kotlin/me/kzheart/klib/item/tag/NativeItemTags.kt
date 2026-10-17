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
import java.util.Optional

/** 当前服务端真正读写的存储位置，二者不代表相同的原版物品结构。 */
enum class ItemTagStorage { LEGACY_ROOT, CUSTOM_DATA }

/**
 * 原生标签的一个小而完整的边界：读取独立快照，编辑后写回同一物品。
 * 旧服务端读写根 NBT；组件服务端仅读写 minecraft:custom_data，保留其他组件。
 * [readItem] / [createItem] 显式处理当前服务端完整物品格式；不做跨版本数据升级。
 * 所有物品操作应在 Bukkit 主线程调用。
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

    /** 完整物品的独立快照，包含材质、数量和当前服务端的标签或组件格式。 */
    @JvmStatic fun readItem(item: ItemStack): NbtCompound {
        require(!InventoryItems.isAir(item)) { "Cannot serialize an air item" }
        return operation { access().readItem(item) }
    }

    /**
     * 从当前服务端完整物品格式创建新物品，不修改输入树或任何已有 ItemStack。
     * 未知材质、空气和非法组件明确失败；不接受仅 custom_data 的树，不执行 DataFixer。
     */
    @JvmStatic fun createItem(tags: NbtCompound): ItemStack {
        val id = tags["id"]
        require(id != null && id.type == NbtType.STRING && (id.value() as String).isNotEmpty()) {
            "Complete item data requires a non-empty string id"
        }
        return operation { access().createItem(tags) }
    }

    internal fun completeItemCount(tags: NbtCompound, storage: ItemTagStorage): Int {
        val legacy = storage == ItemTagStorage.LEGACY_ROOT
        val key = if (legacy) "Count" else "count"
        val expected = if (legacy) NbtType.BYTE else NbtType.INT
        val count = tags[key]
        require(count != null && count.type == expected) { "Complete item data requires $key as $expected" }
        val value = (count.value() as Number).toInt()
        require(value > 0) { "Complete item count must be positive" }
        return value
    }

    internal fun completeItemCustomData(tags: NbtCompound): NbtCompound? {
        val components = tags["components"] as? NbtCompound ?: return null
        val names = listOf("minecraft:custom_data", "custom_data", ":custom_data")
        val present = names.filter { components.contains(it) }
        require(present.size <= 1 && (present.isEmpty() || names.none { components.contains("!$it") })) {
            "Complete item data has duplicate or conflicting custom_data components"
        }
        return present.singleOrNull()?.let { components[it] } as? NbtCompound
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

        // 完整物品能力延迟解析，不改变现有 root/custom_data 入口的可用性。
        private val fullItem by lazy { FullItemCodec() }

        fun readItem(item: ItemStack): NbtCompound {
            val handle = toNative.invoke(null, item)
            val snapshot = decode(fullItem.save(handle))
            if (storage == ItemTagStorage.CUSTOM_DATA) {
                // Registry codec 的 DynamicOps 会把数字 LIST 规范化为数组；业务 NBT 必须保留精确类型。
                val raw = getComponent!!.invoke(handle, component)?.let { customTag!!.get(it) }
                if (raw != null) {
                    val components = snapshot.getCompound("components") ?: NbtCompound()
                    components.putCompound("minecraft:custom_data", decode(raw))
                    snapshot.putCompound("components", components)
                }
            }
            return snapshot
        }

        fun createItem(tags: NbtCompound): ItemStack {
            // Mojang 的宽松 optional 字段可能把非法 count 变为默认 1，不能据 result 存在即当输入有效。
            val count = completeItemCount(tags, storage)
            val exact = if (storage == ItemTagStorage.CUSTOM_DATA) completeItemCustomData(tags) else null
            val handle = fullItem.load(encode(tags))
            if (storage == ItemTagStorage.CUSTOM_DATA) {
                // 其余组件仍由宿主 codec 校验；custom_data 独立恢复，避免 load 再次折叠 LIST 类型。
                // 宿主也可接受 SNBT 字符串形式；仅 compound 需要保留二进制类型，其他合法形式交给 codec。
                if (exact != null && !exact.isEmpty()) setComponent!!.invoke(null, component, handle, encode(exact))
            }
            val item = toBukkit.invoke(null, handle) as ItemStack
            require(!InventoryItems.isAir(item) && item.amount > 0) { "Complete item data produced an empty or unknown item" }
            require(item.amount == count) { "Server item codec normalized count $count to ${item.amount}" }
            return item
        }

        /** 两种持久化格式在此收敛；编码仍复用唯一 NbtBinary，不泄漏注册表/NMS。 */
        private inner class FullItemCodec {
            val save: (Any) -> Any
            val load: (Any) -> Any

            init {
                if (storage == ItemTagStorage.LEGACY_ROOT) {
                    val saver = unique(itemType.methods.filter {
                        !Modifier.isStatic(it.modifiers) && it.returnType == compound &&
                            it.parameterTypes.contentEquals(arrayOf(compound))
                    }, "Complete legacy item save")
                    val constructor = itemType.getDeclaredConstructor(compound).apply { isAccessible = true }
                    save = { handle -> saver.invoke(handle, compound.getDeclaredConstructor().newInstance()) }
                    load = { tag -> constructor.newInstance(tag) }
                } else {
                    val codecType = this@Access.load("com.mojang.serialization.Codec")
                    val codecField = itemType.getField("CODEC")
                    requireLayout(Modifier.isStatic(codecField.modifiers) && codecType.isAssignableFrom(codecField.type), "Complete item codec")
                    val codec = codecField.get(null)
                    val dynamicOps = this@Access.load("com.mojang.serialization.DynamicOps")
                    val nbtOpsType = this@Access.load("net.minecraft.nbt.NbtOps", "net.minecraft.nbt.DynamicOpsNBT")
                    val nbtOps = unique(nbtOpsType.fields.filter {
                        Modifier.isStatic(it.modifiers) && it.type == nbtOpsType
                    }, "NBT operations singleton").get(null)
                    val craftRegistry = this@Access.load("$serverPackage.CraftRegistry")
                    val registryGetter = craftRegistry.getMethod("getMinecraftRegistry")
                    val provider = this@Access.load("net.minecraft.core.HolderLookup\$Provider")
                    val contextFactory = provider.getMethod("createSerializationContext", dynamicOps)
                    fun context(): Any = contextFactory.invoke(registryGetter.invoke(null), nbtOps)
                    val encoder = this@Access.load("com.mojang.serialization.Encoder").getMethod("encodeStart", dynamicOps, Any::class.java)
                    val decoder = this@Access.load("com.mojang.serialization.Decoder").getMethod("parse", dynamicOps, Any::class.java)
                    val resultType = this@Access.load("com.mojang.serialization.DataResult")
                    val resultMethod = resultType.getMethod("result")
                    val errorMethod = resultType.getMethod("error")
                    fun accepted(result: Any): Any {
                        val value = resultMethod.invoke(result) as Optional<*>
                        require(value.isPresent) { "Invalid complete item data: ${errorMethod.invoke(result)}" }
                        return value.get()
                    }
                    save = { handle -> accepted(encoder.invoke(codec, context(), handle)) }
                    load = { tag -> accepted(decoder.invoke(codec, context(), tag)) }
                }
            }
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
