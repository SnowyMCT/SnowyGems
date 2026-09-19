package mc233.`fun`.snowygems.config

/** Pure rules shared by preview and execution. Zero means unlimited. */
object EmbedRules {
    fun rejection(gem: GemConfig, applied: List<String>, lookup: (String) -> GemConfig?): String? {
        if (gem.embed > 0 && applied.count { it == gem.id } >= gem.embed) return "gem.embed-limit"
        if (gem.exclusiveGroup.isNotBlank() && applied.any {
                it != gem.id && lookup(it)?.exclusiveGroup == gem.exclusiveGroup
            }) return "gem.exclusive-group"
        return null
    }
}
