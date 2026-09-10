package mc233.`fun`.snowygems.config

import taboolib.common.platform.function.getDataFolder
import taboolib.common.platform.function.info
import taboolib.common.platform.function.warning
import java.io.File

/**
 * 启动/重载后的配置健康检查。
 * 各注册表使用 releaseResourceFolder(..., replace = false)，因此缺失文件会自动补回；
 * 这里负责把仍然缺失、空目录或旧服配置不完整的情况明确告诉服主，同时绝不覆盖已有文件。
 */
object ConfigurationHealth {
    private val expectedFiles = listOf(
        "config.yml",
        "lang/zh_CN.yml", "lang/zh_TW.yml", "lang/en_US.yml",
        "gui/gui.yml", "gui/rune.yml",
        "gems/RuneGem.yml", "runes/forge.yml"
    )

    fun check() {
        val folder = getDataFolder()
        val missing = expectedFiles.filter { !File(folder, it).isFile }
        if (missing.isEmpty()) {
            info("SnowyGems 配置自检通过：核心配置、语言、菜单与符文文件齐全（已有配置不会被覆盖）")
            return
        }
        warning("SnowyGems 配置自检发现缺失文件：${missing.joinToString(", ")}")
        warning("插件会在下次重载时再次尝试释放默认文件；若文件被自定义资源包或部署脚本删除，请恢复后执行 /sgem reload")
    }
}
