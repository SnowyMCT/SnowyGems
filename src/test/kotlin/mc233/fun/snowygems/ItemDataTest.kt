package mc233.`fun`.snowygems

import mc233.`fun`.snowygems.util.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.*

class ItemDataTest {
    @Test fun roundTripHistoryAndAttributes() {
        val history = ItemTagList().apply { add(ItemTagData("")); add(ItemTagData("符文😀".repeat(20000))) }
        val values = linkedMapOf("id" to ItemTagData("红色符文"), "attribute" to ItemTagData(2.75), "history" to ItemTagData(history))
        val restored = ItemDataCodec.decode(ItemDataCodec.encode(values))
        assertEquals("红色符文", restored["id"]?.asString())
        assertEquals(2.75, restored["attribute"]?.asDouble())
        assertEquals(history.map { it.asString() }, (restored["history"]!!.value as ItemTagList).map { it.asString() })
    }

    @Test fun removedDataHasAnExplicitEmptyPayload() {
        val encoded = ItemDataCodec.encode(emptyMap())
        assertTrue(encoded.isNotEmpty())
        assertTrue(ItemDataCodec.decode(encoded).isEmpty())
    }

    @Test fun corruptDataIsRejected() {
        val encoded = ItemDataCodec.encode(mapOf("id" to ItemTagData("test")))
        assertFails { ItemDataCodec.decode(encoded.copyOf(encoded.size - 1)) }
        assertFails { ItemDataCodec.decode(encoded + byteArrayOf(0)) }
        assertFails { ItemDataCodec.decode(byteArrayOf(0, 0, 0, 2, 0, 0, 0, 0)) }
    }

    private fun legacy(modern: Boolean): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            fun compound(name: String) { out.writeByte(10); out.writeUTF(name) }
            fun string(name: String, value: String) { out.writeByte(8); out.writeUTF(name); out.writeUTF(value) }
            compound("")
            if (modern) { compound("components"); compound("minecraft:custom_data") } else compound("tag")
            string("SnowyGemsId", "红色符文")
            string("otherPlugin", "ignored")
            out.writeByte(9); out.writeUTF("SnowyGemsApplied"); out.writeByte(8); out.writeInt(2)
            out.writeUTF("first"); out.writeUTF("second")
            out.writeByte(6); out.writeUTF("SnowyGemsAttribute"); out.writeDouble(3.5)
            out.writeByte(0)
            if (modern) out.writeByte(0)
            out.writeByte(0)
        }
    }.toByteArray()

    @Test fun importsPreComponentNbt() {
        val data = LegacyItemData.read(legacy(false))
        assertEquals("红色符文", data["SnowyGemsId"])
        assertEquals(listOf("first", "second"), data["SnowyGemsApplied"])
        assertEquals(3.5, data["SnowyGemsAttribute"])
        assertFalse("otherPlugin" in data)
    }

    @Test fun importsCompressedComponentNbt() {
        val bytes = ByteArrayOutputStream().also { GZIPOutputStream(it).use { gzip -> gzip.write(legacy(true)) } }.toByteArray()
        assertEquals(LegacyItemData.read(legacy(false)), LegacyItemData.read(bytes))
    }

    @Test fun malformedLegacyIsNotSilentlyDiscarded() {
        assertFails { LegacyItemData.read(legacy(true).copyOf(20)) }
        assertFails { LegacyItemData.read(byteArrayOf(8, 0, 0)) }
    }
}
