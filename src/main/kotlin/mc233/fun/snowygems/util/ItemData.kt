package mc233.`fun`.snowygems.util

import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.persistence.PersistentDataType
import java.io.*
import java.util.zip.GZIPInputStream

/** Bukkit PDC storage; legacy custom_data is read through Paper's public item serialization API. */
class ItemTagData(val value: Any) {
    fun asString(): String = value.toString()
    fun asDouble(): Double = (value as? Number)?.toDouble() ?: value.toString().toDouble()
}

class ItemTagList : ArrayList<ItemTagData>()

class ItemData(private val values: MutableMap<String, ItemTagData>) {
    operator fun get(key: String): ItemTagData? = values[key]
    operator fun set(key: String, value: ItemTagData) { values[key] = value }
    operator fun set(key: String, value: ItemTagList) { values[key] = ItemTagData(value) }
    fun remove(key: String) { values.remove(key) }

    fun saveTo(item: ItemStack) {
        val meta = item.itemMeta ?: error("Item has no metadata")
        saveTo(meta)
        item.itemMeta = meta
    }

    fun saveTo(meta: ItemMeta) {
        meta.persistentDataContainer.set(KEY, PersistentDataType.BYTE_ARRAY, ItemDataCodec.encode(values))
    }

    companion object {
        val KEY = NamespacedKey("snowygems", "item_data_v1")
    }
}

fun ItemStack.getItemTag(): ItemData {
    if (!hasItemMeta()) return ItemData(linkedMapOf())
    val meta = itemMeta ?: return ItemData(linkedMapOf())
    val stored = meta.persistentDataContainer.get(ItemData.KEY, PersistentDataType.BYTE_ARRAY)
    if (stored != null) return ItemData(ItemDataCodec.decode(stored))
    require(!meta.persistentDataContainer.has(ItemData.KEY)) { "Invalid SnowyGems item data type" }
    // Do not call TabooLib's NMSItemTagLegacy, even as a fallback: its mappings can be broken.
    val method = javaClass.methods.firstOrNull { it.name == "serializeAsBytes" && it.parameterCount == 0 }
        ?: error("This server lacks the public ItemStack.serializeAsBytes API required to read legacy item data")
    val bytes = method.invoke(this) as ByteArray
    val legacy = LegacyItemData.read(bytes)
    return ItemData(legacy.mapValuesTo(linkedMapOf()) { (_, value) ->
        if (value is List<*>) ItemTagData(ItemTagList().apply { value.forEach { add(ItemTagData(it ?: "")) } })
        else ItemTagData(value)
    })
}

/** Versioned, bounded codec. Empty maps are saved too, preventing removed legacy tags from reappearing. */
object ItemDataCodec {
    private const val MAX_BYTES = 16 * 1024 * 1024
    private fun DataOutputStream.text(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES)
        writeInt(bytes.size)
        write(bytes)
    }
    private fun DataInputStream.text(): String {
        val size = readInt()
        require(size in 0..MAX_BYTES && size <= available())
        return ByteArray(size).also { readFully(it) }.toString(Charsets.UTF_8)
    }
    fun encode(values: Map<String, ItemTagData>): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            require(values.size <= 10000)
            out.writeInt(1)
            out.writeInt(values.size)
            values.forEach { (key, data) ->
                out.text(key)
                when (val value = data.value) {
                    is Number -> { out.writeByte(1); out.writeDouble(value.toDouble()) }
                    is ItemTagList -> {
                        require(value.size <= 10000)
                        out.writeByte(2); out.writeInt(value.size)
                        value.forEach { out.text(it.asString()) }
                    }
                    is String -> { out.writeByte(3); out.text(value) }
                    else -> error("Unsupported item data value")
                }
                require(bytes.size() <= MAX_BYTES)
            }
        }
    }.toByteArray()

    fun decode(bytes: ByteArray): MutableMap<String, ItemTagData> = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        require(bytes.size <= MAX_BYTES)
        require(input.readInt() == 1) { "Unsupported item data version" }
        val count = input.readInt().also { require(it in 0..10000) }
        val values = linkedMapOf<String, ItemTagData>()
        repeat(count) {
            val key = input.text()
            require(key !in values) { "Duplicate item data key" }
            values[key] = when (input.readUnsignedByte()) {
                1 -> ItemTagData(input.readDouble())
                2 -> ItemTagData(ItemTagList().apply {
                    repeat(input.readInt().also { require(it in 0..10000) }) { add(ItemTagData(input.text())) }
                })
                3 -> ItemTagData(input.text())
                else -> error("Invalid item data type")
            }
        }
        require(input.available() == 0) { "Trailing item data" }
        values
    }
}

/** Reads standard named NBT from serializeAsBytes, without Minecraft classes or mapped field names. */
object LegacyItemData {
    fun read(bytes: ByteArray): Map<String, Any> {
        val stream = ByteArrayInputStream(bytes)
        val raw = if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) GZIPInputStream(stream) else stream
        val expanded = raw.use { it.readNBytes(16 * 1024 * 1024 + 1) }
        require(expanded.size <= 16 * 1024 * 1024) { "Item NBT too large" }
        val input = DataInputStream(ByteArrayInputStream(expanded))
        require(input.readUnsignedByte() == 10) { "Item NBT root must be a compound" }
        input.readUTF()
        val root = payload(input, 10, 0) as Map<*, *>
        val components = root["components"] as? Map<*, *>
        val custom = (components?.get("minecraft:custom_data") as? Map<*, *>) ?: (root["tag"] as? Map<*, *>) ?: emptyMap<Any, Any>()
        return custom.entries.filter { it.key is String && (it.key as String).startsWith("SnowyGems") }
            .associate { it.key as String to (it.value ?: error("Null legacy tag")) }
    }

    private fun payload(input: DataInputStream, type: Int, depth: Int): Any {
        require(depth <= 64) { "Item NBT too deep" }
        fun length() = input.readInt().also { require(it in 0..1000000) }
        return when (type) {
            1 -> input.readByte()
            2 -> input.readShort()
            3 -> input.readInt()
            4 -> input.readLong()
            5 -> input.readFloat()
            6 -> input.readDouble()
            7 -> ByteArray(length()).also { input.readFully(it) }
            8 -> input.readUTF()
            9 -> {
                val child = input.readUnsignedByte()
                List(length()) { payload(input, child, depth + 1) }
            }
            10 -> linkedMapOf<String, Any>().apply {
                while (true) {
                    val child = input.readUnsignedByte()
                    if (child == 0) break
                    require(size < 100000) { "Item NBT compound too large" }
                    val key = input.readUTF()
                    require(key !in this) { "Duplicate NBT key" }
                    put(key, payload(input, child, depth + 1))
                }
            }
            11 -> IntArray(length()) { input.readInt() }
            12 -> LongArray(length()) { input.readLong() }
            else -> error("Invalid NBT type: $type")
        }
    }
}
