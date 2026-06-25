package com.tvbox.checker.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import com.tvbox.checker.spider.SpiderLoader
import com.tvbox.checker.spider.DrpyEngine
import com.tvbox.checker.spider.XpathParser
import java.util.concurrent.TimeUnit

/**
 * TVBox 站点 API 调用
 *
 * TVBox 站点类型：
 * - type 0: CMS JSON (苹果CMS v10 API) - 直接 HTTP JSON 接口
 * - type 1: CMS XML/JSON (苹果CMS/海洋CMS) - XML 或 JSON
 * - type 3: Spider (JAR/DEX 爬虫) - 使用 DexClassLoader 加载 jar 并反射调用
 *           - csp_* 子类型: 调用 jar 中的 Java 类
 *           - drpy 子类型: 使用 JS 引擎执行 drpy 脚本
 * - type 4: XPath/CSS 选择器规则 - 用 Jsoup 解析 HTML
 *
 * 苹果CMS API 常见接口：
 * - ?ac=list          → 获取分类列表
 * - ?ac=detail        → 获取详情（含播放链接）
 * - ?ac=search&wd=xxx → 搜索
 */
class SiteApi(context: Context? = null) {

    private val spiderLoader: SpiderLoader? = context?.let { SpiderLoader(it) }
    private val drpyEngine: DrpyEngine? = context?.let { DrpyEngine(it) }
    private val xpathParser = XpathParser()

    // 源级别的 spider URL（来自配置的 spider 字段）
    var globalSpiderUrl: String = ""

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 搜索资源 - 根据站点类型分派到不同引擎
     * @return 搜索结果列表
     */
    suspend fun search(site: TvBoxSite, keyword: String): SearchResult {
        val startTime = System.currentTimeMillis()
        val siteName = site.name.ifBlank { site.key }

        return try {
            when {
                // Type 3 - Spider (csp_*) : 使用 DexClassLoader 加载 jar
                site.type == 3 && site.api.startsWith("csp_") -> {
                    searchWithSpider(site, keyword, startTime)
                }
                // Type 3 - DRPY (api 指向 js 文件) : 使用 Rhino JS 引擎
                site.type == 3 && isDrpyApi(site.api) -> {
                    searchWithDrpy(site, keyword, startTime)
                }
                // Type 4 - XPath/CSS 规则 : 使用 Jsoup 解析
                site.type == 4 -> {
                    searchWithXpath(site, keyword, startTime)
                }
                // Type 0/1 - CMS API : 直接 HTTP 请求
                else -> {
                    searchWithCmsApi(site, keyword, startTime)
                }
            }
        } catch (e: Exception) {
            val latency = System.currentTimeMillis() - startTime
            SearchResult(siteName = siteName, siteKey = site.key, items = emptyList(), error = e.message ?: "Unknown error", latency = latency)
        }
    }

    /**
     * Type 0/1 - 苹果CMS API 搜索
     */
    private suspend fun searchWithCmsApi(site: TvBoxSite, keyword: String, startTime: Long): SearchResult {
        val siteName = site.name.ifBlank { site.key }
        return withContext(Dispatchers.IO) {
            val apiUrl = buildSearchUrl(site, keyword)
            if (apiUrl.isBlank()) {
                return@withContext SearchResult(siteName = siteName, siteKey = site.key, items = emptyList(), error = "无法构建搜索URL", latency = 0)
            }

            val request = Request.Builder().url(apiUrl).header("User-Agent", "okhttp/4.12.0").build()
            client.newCall(request).execute().use { response ->
                val latency = System.currentTimeMillis() - startTime
                if (!response.isSuccessful) {
                    return@withContext SearchResult(siteName = siteName, siteKey = site.key, items = emptyList(), error = "HTTP ${response.code}", latency = latency)
                }
                val body = response.body?.string() ?: ""
                val items = parseSearchResult(body, site.type)
                SearchResult(siteName = siteName, siteKey = site.key, items = items, latency = latency)
            }
        }
    }

    /**
     * Type 3 (csp_*) - 使用 Spider JAR 搜索
     */
    private suspend fun searchWithSpider(site: TvBoxSite, keyword: String, startTime: Long): SearchResult {
        val siteName = site.name.ifBlank { site.key }
        val loader = spiderLoader ?: return SearchResult(siteName = siteName, siteKey = site.key, items = emptyList(), error = "SpiderLoader未初始化", latency = 0)

        val spiderUrl = globalSpiderUrl
        if (spiderUrl.isBlank()) {
            return SearchResult(siteName = siteName, siteKey = site.key, items = emptyList(), error = "无spider配置", latency = 0)
        }

        val ext = site.ext?.let { extractExtString(it) } ?: ""
        val spider = loader.getSpider(spiderUrl, site.api, ext)
            ?: return SearchResult(siteName = siteName, siteKey = site.key, items = emptyList(), error = "Spider加载失败: ${site.api}", latency = System.currentTimeMillis() - startTime)

        val resultJson = withContext(Dispatchers.IO) { spider.searchContent(keyword, false) }
        val latency = System.currentTimeMillis() - startTime

        if (resultJson.isBlank()) {
            return SearchResult(siteName = siteName, siteKey = site.key, items = emptyList(), error = "搜索返回空", latency = latency)
        }

        val items = parseSearchResult(resultJson, site.type)
        return SearchResult(siteName = siteName, siteKey = site.key, items = items, latency = latency)
    }

    /**
     * Type 3 (DRPY) - 使用 JS 引擎搜索
     */
    private suspend fun searchWithDrpy(site: TvBoxSite, keyword: String, startTime: Long): SearchResult {
        val siteName = site.name.ifBlank { site.key }
        val engine = drpyEngine ?: return SearchResult(siteName = siteName, siteKey = site.key, items = emptyList(), error = "DrpyEngine未初始化", latency = 0)

        val engineUrl = site.api // drpy2.min.js URL
        val ruleUrl = site.ext?.let { extractExtString(it) } ?: ""
        if (ruleUrl.isBlank()) {
            return SearchResult(siteName = siteName, siteKey = site.key, items = emptyList(), error = "缺少DRPY规则URL", latency = 0)
        }

        val resultJson = engine.search(engineUrl, ruleUrl, keyword)
        val latency = System.currentTimeMillis() - startTime
        val items = parseSearchResult(resultJson, site.type)
        return SearchResult(siteName = siteName, siteKey = site.key, items = items, latency = latency)
    }

    /**
     * Type 4 - 使用 XPath/CSS 选择器搜索
     */
    private suspend fun searchWithXpath(site: TvBoxSite, keyword: String, startTime: Long): SearchResult {
        val siteName = site.name.ifBlank { site.key }
        val extRules = parseExtRules(site.ext)
        val searchUrl = extRules["searchUrl"] ?: ""

        if (searchUrl.isBlank()) {
            return SearchResult(siteName = siteName, siteKey = site.key, items = emptyList(), error = "缺少searchUrl规则", latency = 0)
        }

        val rules = mapOf(
            "list" to (extRules["searchList"] ?: extRules["list"] ?: ""),
            "title" to (extRules["searchTitle"] ?: extRules["title"] ?: "a@title"),
            "url" to (extRules["searchUrl_item"] ?: extRules["url"] ?: "a@href"),
            "img" to (extRules["searchImg"] ?: extRules["img"] ?: "img@src"),
            "desc" to (extRules["searchDesc"] ?: extRules["desc"] ?: "")
        )

        val resultJson = xpathParser.search(searchUrl, keyword, rules)
        val latency = System.currentTimeMillis() - startTime
        val items = parseSearchResult(resultJson, site.type)
        return SearchResult(siteName = siteName, siteKey = site.key, items = items, latency = latency)
    }

    /**
     * 判断 api 是否是 DRPY 引擎 URL
     */
    private fun isDrpyApi(api: String): Boolean {
        return api.contains("drpy") || api.endsWith(".js") || api.contains(".min.js")
    }

    /**
     * 从 ext JsonElement 中提取字符串
     */
    private fun extractExtString(ext: JsonElement): String {
        return when {
            ext is JsonPrimitive && ext.isString -> ext.content
            ext is JsonObject -> Json.encodeToString(JsonObject.serializer(), ext)
            else -> ext.toString()
        }
    }

    /**
     * 解析 type 4 的 ext 规则
     */
    private fun parseExtRules(ext: JsonElement?): Map<String, String> {
        if (ext == null) return emptyMap()
        return try {
            when {
                ext is JsonObject -> {
                    ext.entries.associate { (k, v) ->
                        k to (if (v is JsonPrimitive) v.content else v.toString())
                    }
                }
                ext is JsonPrimitive && ext.isString -> {
                    // 可能是 JSON 字符串
                    val obj = json.parseToJsonElement(ext.content).jsonObject
                    obj.entries.associate { (k, v) ->
                        k to (if (v is JsonPrimitive) v.content else v.toString())
                    }
                }
                else -> emptyMap()
            }
        } catch (e: Exception) {
            emptyMap()
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

    // ==================== 浏览功能 ====================

    data class HomeResult(
        val categories: List<String> = emptyList(),
        val items: List<SearchItem> = emptyList()
    )

    /**
     * 获取站点首页内容（分类+推荐列表）
     */
    suspend fun getHomeContent(site: TvBoxSite): HomeResult {
        return withContext(Dispatchers.IO) {
            try {
                when {
                    // CMS API
                    (site.type == 0 || site.type == 1) && site.api.startsWith("http") -> {
                        val separator = if (site.api.contains("?")) "&" else "?"
                        val url = "${site.api}${separator}ac=list"
                        val request = Request.Builder().url(url).header("User-Agent", "okhttp/4.12.0").build()
                        client.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) return@withContext HomeResult()
                            val body = response.body?.string() ?: ""
                            parseHomeResult(body)
                        }
                    }
                    // Spider csp_*
                    site.type == 3 && site.api.startsWith("csp_") -> {
                        val ext = site.ext?.let { extractExtString(it) } ?: ""
                        val spider = spiderLoader?.getSpider(globalSpiderUrl, site.api, ext)
                            ?: return@withContext HomeResult()
                        val result = spider.homeContent(true)
                        parseHomeResult(result)
                    }
                    // DRPY
                    site.type == 3 && isDrpyApi(site.api) -> {
                        // DRPY 首页需要完整 JS 执行环境，返回空
                        HomeResult()
                    }
                    else -> HomeResult()
                }
            } catch (e: Exception) {
                HomeResult()
            }
        }
    }

    /**
     * 获取分类内容
     */
    suspend fun getCategoryContent(site: TvBoxSite, categoryId: String): List<SearchItem> {
        return withContext(Dispatchers.IO) {
            try {
                when {
                    (site.type == 0 || site.type == 1) && site.api.startsWith("http") -> {
                        val separator = if (site.api.contains("?")) "&" else "?"
                        val url = "${site.api}${separator}ac=detail&t=$categoryId&pg=1"
                        val request = Request.Builder().url(url).header("User-Agent", "okhttp/4.12.0").build()
                        client.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) return@withContext emptyList()
                            val body = response.body?.string() ?: ""
                            parseSearchResult(body, site.type)
                        }
                    }
                    site.type == 3 && site.api.startsWith("csp_") -> {
                        val ext = site.ext?.let { extractExtString(it) } ?: ""
                        val spider = spiderLoader?.getSpider(globalSpiderUrl, site.api, ext)
                            ?: return@withContext emptyList()
                        val result = spider.categoryContent(categoryId, "1", true)
                        parseSearchResult(result, site.type)
                    }
                    else -> emptyList()
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    /**
     * 解析首页返回（提取分类和列表）
     */
    private fun parseHomeResult(body: String): HomeResult {
        return try {
            val jsonObj = json.parseToJsonElement(body).jsonObject
            val categories = mutableListOf<String>()

            // 提取 class 分类列表
            val classList = jsonObj["class"]?.jsonArray
            if (classList != null) {
                for (item in classList) {
                    val obj = item.jsonObject
                    val name = obj["type_name"]?.jsonPrimitive?.contentOrNull ?: continue
                    categories.add(name)
                }
            }

            // 提取 list 内容
            val items = jsonObj["list"]?.jsonArray?.let { list ->
                list.mapNotNull { element ->
                    val obj = element.jsonObject
                    val vodId = obj["vod_id"]?.let { extractStringOrInt(it) } ?: return@mapNotNull null
                    val vodName = obj["vod_name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    SearchItem(
                        vodId = vodId,
                        vodName = vodName,
                        vodPic = obj["vod_pic"]?.jsonPrimitive?.contentOrNull ?: "",
                        typeName = obj["type_name"]?.jsonPrimitive?.contentOrNull ?: "",
                        vodRemarks = obj["vod_remarks"]?.jsonPrimitive?.contentOrNull ?: ""
                    )
                }
            } ?: emptyList()

            HomeResult(categories = categories, items = items)
        } catch (e: Exception) {
            HomeResult()
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
