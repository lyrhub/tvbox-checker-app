package com.tvbox.checker.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/**
 * TVBox 源配置数据结构
 */
@Serializable
data class TvBoxConfig(
    val spider: String = "",
    val sites: List<TvBoxSite> = emptyList(),
    val lives: List<JsonObject> = emptyList(),
    val parses: List<JsonObject> = emptyList(),
    val doh: List<String> = emptyList(),
    val rules: List<JsonObject> = emptyList(),
    val flags: List<String> = emptyList()
)

@Serializable
data class TvBoxSite(
    val key: String = "",
    val name: String = "",
    val type: Int = 0,
    val api: String = "",
    val searchable: Int = 0,
    val quickSearch: Int = 0,
    val filterable: Int = 0,
    val ext: JsonElement? = null,
    val jar: String = "",
    val playerType: Int = -1,
    @SerialName("changeable") val changeable: Int = 0,
    val timeout: Int = 0,
    val categories: List<String> = emptyList()
)

/**
 * 站点测试结果
 */
data class SiteCheckResult(
    val site: TvBoxSite,
    val status: CheckStatus = CheckStatus.PENDING,
    val latency: Long = 0, // ms
    val errorMsg: String = "",
    val isSelected: Boolean = true
)

enum class CheckStatus {
    PENDING,    // 等待测试
    TESTING,    // 测试中
    OK,         // 可连通
    FAILED,     // 不可连通
    TIMEOUT     // 超时
}
