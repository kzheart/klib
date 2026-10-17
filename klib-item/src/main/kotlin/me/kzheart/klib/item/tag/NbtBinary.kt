package me.kzheart.klib.item.tag

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/** 标准未压缩、命名根复合 NBT。唯一的类型编解码层，不依赖 NMS 类布局。 */
internal object NbtBinary {
    const val MAX_BYTES = 16 * 1024 * 1024
    private const val MAX_DEPTH = 512

    fun encode(root: NbtCompound): ByteArray {
        val bytes = LimitedOutput()
        DataOutputStream(bytes).use { out ->
            out.writeByte(NbtType.COMPOUND.id)
            out.writeUTF("")
            writePayload(out, root, 0)
        }
        return bytes.toByteArray()
    }

    fun decode(bytes: ByteArray): NbtCompound {
        require(bytes.size <= MAX_BYTES) { "NBT exceeds $MAX_BYTES bytes" }
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readUnsignedByte() == NbtType.COMPOUND.id) { "NBT root must be a compound" }
        input.readUTF()
        val root = readPayload(input, NbtType.COMPOUND.id, 0) as NbtCompound
        require(input.available() == 0) { "Trailing bytes after NBT root" }
        return root
    }

    private fun writePayload(out: DataOutputStream, tag: NbtTag, depth: Int) {
        require(depth <= MAX_DEPTH) { "NBT nesting exceeds $MAX_DEPTH" }
        when (tag.type) {
            NbtType.BYTE -> out.writeByte((tag.value() as Byte).toInt())
            NbtType.SHORT -> out.writeShort((tag.value() as Short).toInt())
            NbtType.INT -> out.writeInt(tag.value() as Int)
            NbtType.LONG -> out.writeLong(tag.value() as Long)
            NbtType.FLOAT -> out.writeFloat(tag.value() as Float)
            NbtType.DOUBLE -> out.writeDouble(tag.value() as Double)
            NbtType.STRING -> out.writeUTF(tag.value() as String)
            NbtType.BYTE_ARRAY -> (tag.value() as ByteArray).let { out.writeInt(it.size); out.write(it) }
            NbtType.INT_ARRAY -> (tag.value() as IntArray).let { out.writeInt(it.size); it.forEach(out::writeInt) }
            NbtType.LONG_ARRAY -> (tag.value() as LongArray).let { out.writeInt(it.size); it.forEach(out::writeLong) }
            NbtType.LIST -> (tag as NbtList).let {
                out.writeByte(it.elementType()?.id ?: 0)
                out.writeInt(it.size())
                it.forEach { value -> writePayload(out, value, depth + 1) }
            }
            NbtType.COMPOUND -> {
                (tag as NbtCompound).entries().forEach { (key, value) ->
                    out.writeByte(value.type.id)
                    out.writeUTF(key)
                    writePayload(out, value, depth + 1)
                }
                out.writeByte(0)
            }
        }
    }

    private fun readPayload(input: DataInputStream, id: Int, depth: Int): NbtTag {
        require(depth <= MAX_DEPTH) { "NBT nesting exceeds $MAX_DEPTH" }
        return when (id) {
            1 -> NbtTag.byteValue(input.readByte())
            2 -> NbtTag.shortValue(input.readShort())
            3 -> NbtTag.intValue(input.readInt())
            4 -> NbtTag.longValue(input.readLong())
            5 -> NbtTag.floatValue(input.readFloat())
            6 -> NbtTag.doubleValue(input.readDouble())
            7 -> NbtTag.bytes(ByteArray(length(input, 1)).also { input.readFully(it) })
            8 -> NbtTag.string(input.readUTF())
            9 -> {
                val elementId = input.readUnsignedByte()
                val count = length(input, 1)
                require(elementId in 1..12 || (elementId == 0 && count == 0)) { "Invalid NBT list type: $elementId" }
                NbtList(List(count) { readPayload(input, elementId, depth + 1) })
            }
            10 -> {
                val result = NbtCompound()
                while (true) {
                    val childId = input.readUnsignedByte()
                    if (childId == 0) break
                    val name = input.readUTF()
                    result[name] = readPayload(input, childId, depth + 1)
                }
                result
            }
            11 -> NbtTag.ints(IntArray(length(input, 4)) { input.readInt() })
            12 -> NbtTag.longs(LongArray(length(input, 8)) { input.readLong() })
            else -> throw IllegalArgumentException("Invalid NBT tag type: $id")
        }
    }

    private fun length(input: DataInputStream, width: Int): Int {
        val count = input.readInt()
        require(count >= 0 && count <= input.available() / width) { "Invalid or truncated NBT collection length: $count" }
        return count
    }

    /** 服务端导出与本地编码共用上限，防止先无限分配再检查。 */
    class LimitedOutput : ByteArrayOutputStream() {
        override fun write(value: Int) { checkLimit(1); super.write(value) }
        override fun write(bytes: ByteArray, offset: Int, length: Int) { checkLimit(length); super.write(bytes, offset, length) }
        private fun checkLimit(length: Int) {
            if (length < 0 || count > MAX_BYTES - length) throw IOException("NBT exceeds $MAX_BYTES bytes")
        }
    }
}
