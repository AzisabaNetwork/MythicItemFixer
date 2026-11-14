package net.azisaba.mythicitemFixer

import java.io.DataInput
import java.io.DataOutput
import java.io.IOException

/**
 * Minimal NBT reader/writer for offline playerdata migration. It keeps the original tag order so a
 * rewritten file stays as close to the input as possible.
 */
object NbtIo {
    private const val MAX_DEPTH = 128
    private const val MAX_LENGTH = 64 * 1024 * 1024

    sealed class Tag(val type: Byte) {
        class ByteTag(val value: Byte) : Tag(1)
        class ShortTag(val value: Short) : Tag(2)
        class IntTag(val value: Int) : Tag(3)
        class LongTag(val value: Long) : Tag(4)
        class FloatTag(val value: Float) : Tag(5)
        class DoubleTag(val value: Double) : Tag(6)
        class ByteArrayTag(val value: ByteArray) : Tag(7)
        class StringTag(val value: String) : Tag(8)
        class ListTag(val elementType: Byte, val elements: MutableList<Tag>) : Tag(9)
        class CompoundTag(val value: LinkedHashMap<String, Tag>) : Tag(10)
        class IntArrayTag(val value: IntArray) : Tag(11)
        class LongArrayTag(val value: LongArray) : Tag(12)
    }

    @Throws(IOException::class)
    @JvmStatic
    fun readRoot(input: DataInput): Tag.CompoundTag {
        val type = input.readByte()
        if (type.toInt() != 10) throw IOException("NBT root is not a compound (type=$type)")
        input.readUTF()
        return readPayload(input, type, 0) as Tag.CompoundTag
    }

    @Throws(IOException::class)
    @JvmStatic
    fun writeRoot(output: DataOutput, root: Tag.CompoundTag) {
        output.writeByte(10)
        output.writeUTF("")
        writePayload(output, root, 0)
    }

    @Throws(IOException::class)
    private fun readPayload(input: DataInput, type: Byte, depth: Int): Tag {
        if (depth > MAX_DEPTH) throw IOException("NBT nesting is too deep")
        return when (type.toInt()) {
            1 -> Tag.ByteTag(input.readByte())
            2 -> Tag.ShortTag(input.readShort())
            3 -> Tag.IntTag(input.readInt())
            4 -> Tag.LongTag(input.readLong())
            5 -> Tag.FloatTag(input.readFloat())
            6 -> Tag.DoubleTag(input.readDouble())
            7 -> {
                val value = ByteArray(checkedLength(input.readInt()))
                input.readFully(value)
                Tag.ByteArrayTag(value)
            }
            8 -> Tag.StringTag(input.readUTF())
            9 -> {
                val elementType = input.readByte()
                val length = checkedLength(input.readInt())
                val elements = ArrayList<Tag>(length)
                repeat(length) { elements.add(readPayload(input, elementType, depth + 1)) }
                Tag.ListTag(elementType, elements)
            }
            10 -> {
                val value = LinkedHashMap<String, Tag>()
                while (true) {
                    val childType = input.readByte()
                    if (childType.toInt() == 0) break
                    val name = input.readUTF()
                    value[name] = readPayload(input, childType, depth + 1)
                }
                Tag.CompoundTag(value)
            }
            11 -> {
                val value = IntArray(checkedLength(input.readInt()))
                for (index in value.indices) value[index] = input.readInt()
                Tag.IntArrayTag(value)
            }
            12 -> {
                val value = LongArray(checkedLength(input.readInt()))
                for (index in value.indices) value[index] = input.readLong()
                Tag.LongArrayTag(value)
            }
            else -> throw IOException("Unsupported NBT tag type $type")
        }
    }

    @Throws(IOException::class)
    private fun writePayload(output: DataOutput, tag: Tag, depth: Int) {
        if (depth > MAX_DEPTH) throw IOException("NBT nesting is too deep")
        when (val value = tag) {
            is Tag.ByteTag -> output.writeByte(value.value.toInt())
            is Tag.ShortTag -> output.writeShort(value.value.toInt())
            is Tag.IntTag -> output.writeInt(value.value)
            is Tag.LongTag -> output.writeLong(value.value)
            is Tag.FloatTag -> output.writeFloat(value.value)
            is Tag.DoubleTag -> output.writeDouble(value.value)
            is Tag.ByteArrayTag -> {
                output.writeInt(value.value.size)
                output.write(value.value)
            }
            is Tag.StringTag -> output.writeUTF(value.value)
            is Tag.ListTag -> {
                output.writeByte(value.elementType.toInt())
                output.writeInt(value.elements.size)
                for (element in value.elements) writePayload(output, element, depth + 1)
            }
            is Tag.CompoundTag -> {
                for ((name, child) in value.value) {
                    output.writeByte(child.type.toInt())
                    output.writeUTF(name)
                    writePayload(output, child, depth + 1)
                }
                output.writeByte(0)
            }
            is Tag.IntArrayTag -> {
                output.writeInt(value.value.size)
                for (element in value.value) output.writeInt(element)
            }
            is Tag.LongArrayTag -> {
                output.writeInt(value.value.size)
                for (element in value.value) output.writeLong(element)
            }
        }
    }

    @Throws(IOException::class)
    private fun checkedLength(length: Int): Int {
        if (length < 0 || length > MAX_LENGTH) throw IOException("Invalid NBT array length $length")
        return length
    }
}
