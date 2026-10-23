package com.slideindex.app.settings

import android.content.Context
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

object SearchEngineStore {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val listSerializer = ListSerializer(SearchEngineConfig.serializer())

    fun encode(engines: List<SearchEngineConfig>): String =
        json.encodeToString(listSerializer, engines.sortedBy { it.sortOrder })

    fun decode(context: Context, raw: String?): List<SearchEngineConfig> {
        if (raw.isNullOrBlank()) return SearchEngineCatalog.defaultEngines(context)
        return runCatching {
            json.decodeFromString(listSerializer, raw)
        }.getOrElse { SearchEngineCatalog.defaultEngines(context) }
    }

    /**
     * 解析“没有默认值”的引擎列表（例如拖拽流转栏里的文字副本）：
     * 空串/坏数据都返回空列表，不会像 [decode] 那样回落成默认目录。
     */
    fun decodeOrEmpty(raw: String?): List<SearchEngineConfig> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(listSerializer, raw) }.getOrElse { emptyList() }
    }

    fun textPickPanelEngines(engines: List<SearchEngineConfig>): List<SearchEngineConfig> =
        engines.filter { it.isTextSearchEngine() }.sortedBy { it.sortOrder }

    fun imageSharePanelEngines(engines: List<SearchEngineConfig>): List<SearchEngineConfig> =
        engines.filter {
            it.engineType == SearchEngineType.SHARE_IMAGE_TO_APP && it.showInPickPanel
        }.sortedBy { it.sortOrder }

    /** 拖拽流转栏用的图片目标：所有「分享到 App」引擎，不看取词面板的隐藏开关。 */
    fun imageFlowEngines(engines: List<SearchEngineConfig>): List<SearchEngineConfig> =
        engines.filter { it.engineType == SearchEngineType.SHARE_IMAGE_TO_APP }.sortedBy { it.sortOrder }

    fun fileSharePanelEngines(engines: List<SearchEngineConfig>): List<SearchEngineConfig> {
        val configured = engines.filter {
            it.engineType == SearchEngineType.SHARE_FILE_TO_APP && it.showInPickPanel
        }.sortedBy { it.sortOrder }
        if (configured.isNotEmpty()) return configured
        return defaultFileShareEngines()
    }

    fun defaultFileShareEngines(): List<SearchEngineConfig> = listOf(
        SearchEngineConfig(
            id = "file_share_wechat",
            name = "发送给微信",
            targetPackage = "com.tencent.mm",
            engineType = SearchEngineType.SHARE_FILE_TO_APP,
            sortOrder = 0,
        ),
        SearchEngineConfig(
            id = "file_share_qq",
            name = "发送给QQ",
            targetPackage = "com.tencent.mobileqq",
            engineType = SearchEngineType.SHARE_FILE_TO_APP,
            sortOrder = 1,
        ),
        SearchEngineConfig(
            id = "file_share_dingtalk",
            name = "发送给钉钉",
            targetPackage = "com.alibaba.android.rimet",
            engineType = SearchEngineType.SHARE_FILE_TO_APP,
            sortOrder = 2,
        ),
        SearchEngineConfig(
            id = "file_share_lark",
            name = "发送给飞书",
            targetPackage = "com.ss.android.lark",
            engineType = SearchEngineType.SHARE_FILE_TO_APP,
            sortOrder = 3,
        ),
    )

    fun textSettingsEngines(engines: List<SearchEngineConfig>): List<SearchEngineConfig> =
        engines.filter {
            it.engineType != SearchEngineType.SHARE_IMAGE_TO_APP &&
                it.engineType != SearchEngineType.SHARE_FILE_TO_APP
        }.sortedBy { it.sortOrder }

    /**
     * 按 id 解析文本搜索引擎。
     *
     * 解析范围是全部文本引擎（含已移入隐藏池的「[SearchEngineConfig.showInPickPanel] = false」项）——
     * 隐藏只表示不在面板网格里占位，不代表不能作为默认引擎使用。
     */
    fun findTextEngineById(
        engines: List<SearchEngineConfig>,
        id: String?,
    ): SearchEngineConfig? {
        if (id.isNullOrBlank()) return null
        return textSettingsEngines(engines).find { it.id == id }
    }

    fun mergeEngines(
        existing: List<SearchEngineConfig>,
        imported: List<SearchEngineConfig>,
        replaceExisting: Boolean,
    ): List<SearchEngineConfig> {
        if (replaceExisting) {
            return imported.mapIndexed { index, engine -> engine.copy(sortOrder = index) }
        }
        val usedNames = existing.map { it.name.lowercase() }.toMutableSet()
        val merged = existing.toMutableList()
        var order = (existing.maxOfOrNull { it.sortOrder } ?: -1) + 1
        imported.forEach { engine ->
            val key = engine.name.lowercase()
            if (key in usedNames) return@forEach
            usedNames += key
            merged += engine.copy(
                id = java.util.UUID.randomUUID().toString(),
                sortOrder = order++,
            )
        }
        return merged
    }
}
