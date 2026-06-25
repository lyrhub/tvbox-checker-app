package com.tvbox.checker.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * TVBox 站点 API 调用
 *
 * TVBox 站点类型：
 * - type 0: CMS JSON (苹果CMS v10 API) - 直接 HTTP JSON 接口
 * - type 1: CMS XML/JSON (苹果CMS/海洋CMS) - XML 或 JSON
 * - type 3: Spider (JAR/DEX 爬虫) - 需要 spider 环境，这里仅测试 ext URL
 * - type 4: XPath/JSON Path 自定义规则
 *
 * 苹果CMS API 常见接口：
 * - ?ac=list          → 获取分类列表
 * - ?ac=detail        → 获取详情（含播放链接）
 * - ?ac=search&wd=xxx → 搜索
 */
class SiteApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 搜索资源
     * @return 搜索结果列表
     */
    suspend fun search(site: TvBoxSite, keyword: String): SearchResult {
        return withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            try {
                val apiUrl = buildSearchUrl(site, keyword)
                if (apiUrl.isBlank()) {
                    return@withContext SearchResult(
                        siteName = site.name.ifBlank { site.key },
                        siteKey = site.key,
                        items = emptyList(),
                        error = "无法构建搜索URL (type=${site.type})",
                        latency = 0
                    )
                }

                val request = Request.Builder()
                    .url(apiUrl)
                    .header("User-Agent", "okhttp/4.12.0")
                    .build()

                client.newCall(request).execute().use { response ->
                    val latency = System.currentTimeMillis() - startTime
                    if (!response.isSuccessful) {
                        return@withContext SearchResult(
                            siteName = site.name.ifBlank { site.key },
                            siteKey = site.key,
                            items = emptyList(),
                            error = "HTTP ${response.code}",
                            latency = latency
                        )
                    }

                    val body = response.body?.string() ?: ""
                    val items = parseSearchResult(body, site.type)
                    SearchResult(
                        siteName = site.name.ifBlank { site.key },
                        siteKey = site.key,
                        items = items,
                        latency = latency
                    )
                }
            } catch (e: Exception) {
                val latency = System.currentTimeMillis() - startTime
                SearchResult(
                    siteName = site.name.ifBlank { site.key },
                    siteKey = site.key,
                    items = emptyList(),
                    error = e.message ?: "Unknown error",
                    latency = latency
                )
            }
        }
    }

    /**
     * 获取资源详情（含播放链接）
     */
    suspend fun getDetail(site: TvBoxSite, vodId: String): DetailResult {
        return withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            try {
                val apiUrl = buildDetailUrl(site, vodId)
                if (apiUrl.isBlank()) {
                    return@withContext DetailResult(error = "无法构建详情URL")
                }

                val request = Request.Builder()
                    .url(apiUrl)
                    .header("User-Agent", "okhttp/4.12.0")
                    .build()

                client.newCall(request).execute().use { response ->
                    val latency = System.currentTimeMillis() - startTime
                    if (!response.isSuccessful) {
                        return@withContext DetailResult(error = "HTTP ${response.code}", latency = latency)
                    }

                    val body = response.body?.string() ?: ""
                    parseDetailResult(body, site.type, latency)
                }
            } catch (e: Exception) {
                DetailResult(error = e.message ?: "Unknown error", latency = System.currentTimeMillis() - startTime)
            }
        }
    }

    /**
     * 测试资源链接的可用性和速度
     * 支持 m3u8, mp4 等视频链接
     */
    suspend fun testPlayUrl(url: String): PlayTestResult {
        return withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            try {
                // 对 m3u8 链接，先获取内容验证有效性
                val isM3u8 = url.contains(".m3u8") || url.contains("m3u8")
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36")
                    .apply {
                        if (!isM3u8) head() // 非 m3u8 用 HEAD 请求节省流量
                    }
                    .build()

                client.newCall(request).execute().use { response ->
                    val latency = System.currentTimeMillis() - startTime
                    val contentType = response.header("Content-Type") ?: ""
                    val contentLength = response.header("Content-Length")?.toLongOrNull() ?: 0

                    if (!response.isSuccessful) {
                        return@withContext PlayTestResult(
                            url = url,
                            status = PlayStatus.FAILED,
                            latency = latency,
                            error = "HTTP ${response.code}"
                        )
                    }

                    // 验证 m3u8 内容
                    if (isM3u8) {
                        val body = response.body?.string() ?: ""
                        val valid = body.contains("#EXTM3U") || body.contains("#EXT-X-") || body.contains(".ts")
                        if (!valid) {
                            return@withContext PlayTestResult(
                                url = url,
                                status = PlayStatus.INVALID,
                                latency = latency,
                                error = "非有效m3u8内容"
                            )
                        }
                    }

                    PlayTestResult(
                        url = url,
                        status = PlayStatus.OK,
                        latency = latency,
                        contentType = contentType,
                        contentLength = contentLength
                    )
                }
            } catch (e: Exception) {
                val latency = System.currentTimeMillis() - startTime
                PlayTestResult(
                    url = url,
                    status = if (latency >= 14000) PlayStatus.TIMEOUT else PlayStatus.FAILED,
                    latency = latency,
                    error = e.message ?: "Unknown error"
                )
            }
        }
    }

    /**
     * 构建搜索 URL
     * 苹果CMS 格式: api?ac=search&wd=keyword
     */
    private fun buildSearchUrl(site: TvBoxSite, keyword: String): String {
        val api = site.api
        if (api.isBlank()) return ""

        // type 0/1: CMS 接口
        if (site.type == 0 || site.type == 1) {
            if (api.startsWith("http://") || api.startsWith("https://")) {
                val separator = if (api.contains("?")) "&" else "?"
                return "${api}${separator}ac=search&wd=${java.net.URLEncoder.encode(keyword, "UTF-8")}"
            }
        }

        // type 3: Spider - 无法直接搜索，尝试用 ext 中的 URL
        if (site.type == 3) {
            // Spider 类型站点需要 jar 环境，这里无法直接调用
            // 但如果 ext 有直接可用的 URL 接口，可以尝试
            val extUrl = getExtApiUrl(site)
            if (extUrl.isNotBlank()) {
                val separator = if (extUrl.contains("?")) "&" else "?"
                return "${extUrl}${separator}ac=search&wd=${java.net.URLEncoder.encode(keyword, "UTF-8")}"
            }
            return ""
        }

        // type 4: 自定义规则 - 暂不支持
        return ""
    }

    /**
     * 构建详情 URL
     */
    private fun buildDetailUrl(site: TvBoxSite, vodId: String): String {
        val api = site.api
        if (api.isBlank()) return ""

        if (site.type == 0 || site.type == 1) {
            if (api.startsWith("http://") || api.startsWith("https://")) {
                val separator = if (api.contains("?")) "&" else "?"
                return "${api}${separator}ac=detail&ids=${java.net.URLEncoder.encode(vodId, "UTF-8")}"
            }
        }

        if (site.type == 3) {
            val extUrl = getExtApiUrl(site)
            if (extUrl.isNotBlank()) {
                val separator = if (extUrl.contains("?")) "&" else "?"
                return "${extUrl}${separator}ac=detail&ids=${java.net.URLEncoder.encode(vodId, "UTF-8")}"
            }
        }

        return ""
    }

    /**
     * 从 ext 字段提取可用的 API URL
     */
    private fun getExtApiUrl(site: TvBoxSite): String {
        val ext = site.ext ?: return ""
        when {
            ext is JsonPrimitive && ext.isString -> {
                val extStr = ext.content
                if (extStr.startsWith("http://") || extStr.startsWith("https://")) {
                    // 如果是 API URL（非 js/json 文件），直接返回
                    if (!extStr.endsWith(".js") && !extStr.endsWith(".json") && !extStr.endsWith(".py")) {
                        return extStr
                    }
                }
            }
            ext is JsonObject -> {
                val url = ext["url"]
                if (url is JsonPrimitive && url.isString && url.content.startsWith("http")) {
                    return url.content
                }
            }
        }
        return ""
    }

    /**
     * 解析搜索结果 JSON
     * 苹果CMS 返回格式: { "list": [{ "vod_id": "1", "vod_name": "xxx", "vod_pic": "...", "type_name": "..." }] }
     */
    private fun parseSearchResult(body: String, siteType: Int): List<SearchItem> {
        return try {
            val jsonObj = json.parseToJsonElement(body).jsonObject
            val list = jsonObj["list"]?.jsonArray ?: return emptyList()

            list.mapNotNull { element ->
                val obj = element.jsonObject
                val vodId = obj["vod_id"]?.let { extractStringOrInt(it) } ?: return@mapNotNull null
                val vodName = obj["vod_name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null

                SearchItem(
                    vodId = vodId,
                    vodName = vodName,
                    vodPic = obj["vod_pic"]?.jsonPrimitive?.contentOrNull ?: "",
                    typeName = obj["type_name"]?.jsonPrimitive?.contentOrNull ?: "",
                    vodRemarks = obj["vod_remarks"]?.jsonPrimitive?.contentOrNull ?: "",
                    vodYear = obj["vod_year"]?.let { extractStringOrInt(it) } ?: ""
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 解析详情结果
     * 苹果CMS 详情格式: { "list": [{ "vod_play_from": "xxx$$$yyy", "vod_play_url": "第1集$url#第2集$url$$..." }] }
     */
    private fun parseDetailResult(body: String, siteType: Int, latency: Long): DetailResult {
        return try {
            val jsonObj = json.parseToJsonElement(body).jsonObject
            val list = jsonObj["list"]?.jsonArray ?: return DetailResult(error = "无 list 字段", latency = latency)
            if (list.isEmpty()) return DetailResult(error = "list 为空", latency = latency)

            val vod = list[0].jsonObject
            val vodName = vod["vod_name"]?.jsonPrimitive?.contentOrNull ?: ""
            val playFrom = vod["vod_play_from"]?.jsonPrimitive?.contentOrNull ?: ""
            val playUrl = vod["vod_play_url"]?.jsonPrimitive?.contentOrNull ?: ""

            // 解析播放源和链接
            // vod_play_from: "源1$$$源2$$$源3"
            // vod_play_url: "第1集$url1#第2集$url2$$$第1集$url3#第2集$url4"
            val sources = playFrom.split("$$$").filter { it.isNotBlank() }
            val urlGroups = playUrl.split("$$$")

            val playLinks = mutableListOf<PlaySource>()
            for (i in sources.indices) {
                val sourceName = sources[i]
                val urls = if (i < urlGroups.size) urlGroups[i] else ""
                val episodes = urls.split("#").mapNotNull { ep ->
                    val parts = ep.split("$")
                    if (parts.size >= 2) {
                        Episode(name = parts[0], url = parts[1])
                    } else null
                }
                if (episodes.isNotEmpty()) {
                    playLinks.add(PlaySource(name = sourceName, episodes = episodes))
                }
            }

            DetailResult(
                vodName = vodName,
                playSources = playLinks,
                latency = latency
            )
        } catch (e: Exception) {
            DetailResult(error = "解析失败: ${e.message}", latency = latency)
        }
    }

    private fun extractStringOrInt(element: JsonElement): String {
        return when {
            element is JsonPrimitive && element.isString -> element.content
            element is JsonPrimitive -> element.content
            else -> ""
        }
    }

    fun shutdown() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}

// --- 数据类 ---

data class SearchResult(
    val siteName: String,
    val siteKey: String,
    val items: List<SearchItem>,
    val error: String? = null,
    val latency: Long = 0
)

data class SearchItem(
    val vodId: String,
    val vodName: String,
    val vodPic: String = "",
    val typeName: String = "",
    val vodRemarks: String = "",
    val vodYear: String = ""
)

data class DetailResult(
    val vodName: String = "",
    val playSources: List<PlaySource> = emptyList(),
    val error: String? = null,
    val latency: Long = 0
)

data class PlaySource(
    val name: String,
    val episodes: List<Episode>
)

data class Episode(
    val name: String,
    val url: String
)

data class PlayTestResult(
    val url: String,
    val status: PlayStatus,
    val latency: Long = 0,
    val contentType: String = "",
    val contentLength: Long = 0,
    val error: String? = null
)

enum class PlayStatus {
    OK,       // 可播放
    FAILED,   // 不可用
    TIMEOUT,  // 超时
    INVALID   // 内容无效
}
