package com.tvbox.checker.data

import kotlinx.serialization.json.*

/**
 * TVBox 源 JSON 解析器
 * 支持标准 TVBox JSON 格式，兼容带注释和 BOM 的文件
 */
object SourceParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /**
     * 解析 TVBox 源 JSON 文本
     */
    fun parse(text: String): TvBoxConfig {
        // 清理 BOM、注释
        val cleaned = text
            .removePrefix("\uFEFF")
            .trim()
            .replace(Regex("^\\s*//.*$", RegexOption.MULTILINE), "")
            .trim()

        return json.decodeFromString<TvBoxConfig>(cleaned)
    }

    /**
     * 将选中的站点导出为新的 TVBox 源 JSON
     */
    fun export(
        originalConfig: TvBoxConfig,
        selectedSites: List<TvBoxSite>,
        keepLives: Boolean = true,
        keepParses: Boolean = true
    ): String {
        val output = buildJsonObject {
            if (originalConfig.spider.isNotBlank()) {
                put("spider", originalConfig.spider)
            }
            putJsonArray("sites") {
                for (site in selectedSites) {
                    add(json.encodeToJsonElement(site))
                }
            }
            if (keepLives && originalConfig.lives.isNotEmpty()) {
                putJsonArray("lives") {
                    for (live in originalConfig.lives) add(live)
                }
            }
            if (keepParses && originalConfig.parses.isNotEmpty()) {
                putJsonArray("parses") {
                    for (parse in originalConfig.parses) add(parse)
                }
            }
            if (originalConfig.doh.isNotEmpty()) {
                putJsonArray("doh") {
                    for (d in originalConfig.doh) add(d)
                }
            }
            if (originalConfig.flags.isNotEmpty()) {
                putJsonArray("flags") {
                    for (f in originalConfig.flags) add(f)
                }
            }
        }

        return Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), output)
    }
}
