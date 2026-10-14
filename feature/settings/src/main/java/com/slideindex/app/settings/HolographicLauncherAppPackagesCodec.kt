package com.slideindex.app.settings

/**
 * 全息启动器「自添加应用」列表的编解码。
 *
 * 为什么不用 `stringSetPreferencesKey` 直接存：包名集合**没有顺序**，读回来会被 DataStore 打乱，
 * 而全息启动器的球面位置是按列表顺序用黄金角铺点的 —— 顺序一变，用户看到的应用位置每次都不同。
 * 这里沿用仓库里已有集合型偏好的通行做法（见 `ExcludedAppScopesCodec`、`QuickLauncherItemCodec`）：
 * 存成 **单元素 Set 的一根编码串**，Set 的包装只为复用 DataStore 的类型化键。
 *
 * 单个包名绝不会包含 [ENTRY_SEPARATOR]（ASCII 记录分隔符），因此不需要转义。
 */
object HolographicLauncherAppPackagesCodec {

    /** ASCII RS，包名里不可能出现。 */
    private const val ENTRY_SEPARATOR = '\u001E'

    fun encode(packages: List<String>): Set<String> {
        val normalized = normalize(packages)
        if (normalized.isEmpty()) return emptySet()
        return setOf(normalized.joinToString(ENTRY_SEPARATOR.toString()))
    }

    fun decode(encoded: Set<String>?): List<String> {
        val raw = encoded?.firstOrNull() ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return normalize(raw.split(ENTRY_SEPARATOR))
    }

    /** 去掉空项与重复项（保留首次出现的顺序），并夹住长度上限。 */
    fun normalize(packages: List<String>): List<String> {
        val seen = LinkedHashSet<String>(packages.size)
        packages.forEach { pkg ->
            val trimmed = pkg.trim()
            if (trimmed.isNotEmpty()) seen += trimmed
        }
        return seen.take(HolographicLauncherSettings.MAX_APP_PACKAGES)
    }
}
