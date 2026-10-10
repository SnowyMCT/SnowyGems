package mc233.`fun`.snowygems

import mc233.`fun`.snowygems.gui.InGameEditor
import taboolib.library.xseries.XMaterial
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 编辑器宝石预览图标的外观判定.
 *
 * 背景: 新版默认内容里大量宝石只写 `Texture`(玩家头颅纹理)不写 `Material`, 早先的预览只读 Material,
 * 于是 195 颗里有 125 颗显示成一张纸. 这里钉住判定规则: 有 Texture 就用 PLAYER_HEAD + 纹理,
 * 否则用 Material(缺省 PAPER).
 */
class EditorIconTest {

    @Test fun `texture based gem previews as a textured player head`() {
        val fields = mapOf<String, Any?>("Display" to "&b耐久宝石", "Texture" to "eyJ0ZXh0dXJlcyI6...")
        assertEquals("PLAYER_HEAD", InGameEditor.gemIconMaterialName(fields))
        assertEquals("eyJ0ZXh0dXJlcyI6...", InGameEditor.gemIconTexture(fields))
    }

    @Test fun `material based gem previews with its own material`() {
        val fields = mapOf<String, Any?>("Material" to "AMETHYST_SHARD")
        assertEquals("AMETHYST_SHARD", InGameEditor.gemIconMaterialName(fields))
        assertNull(InGameEditor.gemIconTexture(fields))
    }

    @Test fun `blank or missing values fall back to paper without texture`() {
        assertEquals("PAPER", InGameEditor.gemIconMaterialName(emptyMap()))
        assertEquals("PAPER", InGameEditor.gemIconMaterialName(mapOf("Texture" to "   ", "Material" to "")))
        assertNull(InGameEditor.gemIconTexture(mapOf("Texture" to "  ")))
    }

    @Test fun `texture wins over a configured material`() {
        // 同一颗宝石同时写了 Material 和 Texture 时, 必须走纹理(否则实物是有纹理的头, 预览却是原版材质)
        val fields = mapOf<String, Any?>("Material" to "PAPER", "Texture" to "eyJ0ZXh0dXJlcyI6...")
        assertEquals("PLAYER_HEAD", InGameEditor.gemIconMaterialName(fields))
    }

    @Test fun `unknown material names degrade to paper instead of throwing`() {
        assertEquals(XMaterial.PLAYER_HEAD, InGameEditor.gemIconMaterial(mapOf("Texture" to "eyJ0")))
        assertEquals(XMaterial.AMETHYST_SHARD, InGameEditor.gemIconMaterial(mapOf("Material" to "AMETHYST_SHARD")))
        assertEquals(XMaterial.PAPER, InGameEditor.gemIconMaterial(mapOf("Material" to "NOT_A_MATERIAL")))
    }
}
