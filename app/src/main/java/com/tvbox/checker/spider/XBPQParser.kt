package com.tvbox.checker.spider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * xBPQ / XBiubiu 规则解析器
 *
 * TVBox 中 csp_XBPQ 和 csp_XBiubiu 类型站点使用自定义 JSON 规则来解析 HTML。
 * 规则格式示例：
 * {
 *   "站名": "奇乐影视",
 *   "主页url": "https://www.qiletv.net",
 *   "搜索url": "https://www.qiletv.net/search.php?searchword={wd}",
 *   "搜索数组": "<li class=\"fed-list-item&&</li>",
 *   "搜索图片": "data-original=\"&&\"",
 *   "搜索标题": "fed-list-title*>&&</a>",
 *   "搜索链接": "href=\"&&\"",
 *   ...
 * }
 *
 * 规则提取逻辑：
 * 1. "&&" 表示提取位置（左边是前缀匹配，右边是后缀匹配）
 * 2. 先用"数组"规则将 HTML 分割成多个块
 * 3. 对每个块用"标题"/"链接"/"图片"规则提取字段
 */
class XBPQParser {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // 缓存规则 JSON
    private val ruleCache = mutableMapOf<String, JsonObject>()

    /**
     * 使用 xBPQ 规则搜索
     * @param extUrl ext 字段中的规则 JSON URL
     * @param keyword 搜索关键词
     */
    suspend fun search(extUrl: String, keyword: String): String {
        return withContext(Dispatchers.IO) {
            try {
                val rules = loadRules(extUrl) ?: return@withContext """{"list":[],"error":"规则加载失败"}"""

                val searchUrl = rules["搜索url"]?.jsonPrimitive?.contentOrNull ?: ""
                if (searchUrl.isBlank()) {
                    return@withContext """{"list":[],"error":"无搜索URL"}"""
                }

                val url = searchUrl.replace("{wd}", java.net.URLEncoder.encode(keyword, "UTF-8"))
                    .replace("**", java.net.URLEncoder.encode(keyword, "UTF-8"))

                val html = fetchHtml(url, rules) ?: return@withContext """{"list":[],"error":"页面请求失败"}"""

                val arrayRule = rules["搜索数组"]?.jsonPrimitive?.contentOrNull ?: rules["数组"]?.jsonPrimitive?.contentOrNull ?: ""
                val titleRule = rules["搜索标题"]?.jsonPrimitive?.contentOrNull ?: rules["标题"]?.jsonPrimitive?.contentOrNull ?: ""
                val linkRule = rules["搜索链接"]?.jsonPrimitive?.contentOrNull ?: rules["链接"]?.jsonPrimitive?.contentOrNull ?: ""
                val imgRule = rules["搜索图片"]?.jsonPrimitive?.contentOrNull ?: rules["图片"]?.jsonPrimitive?.contentOrNull ?: ""
                val descRule = rules["搜索副标题"]?.jsonPrimitive?.contentOrNull ?: rules["副标题"]?.jsonPrimitive?.contentOrNull ?: ""

                val homeUrl = rules["主页url"]?.jsonPrimitive?.contentOrNull ?: ""

                val blocks = splitByRule(html, arrayRule)
                val items = blocks.mapNotNull { block ->
                    val title = extractByRule(block, titleRule)
                    var link = extractByRule(block, linkRule)
                    val img = extractByRule(block, imgRule)
                    val desc = extractByRule(block, descRule)

                    if (title.isBlank()) return@mapNotNull null

                    // 补全相对链接
                    if (link.isNotBlank() && !link.startsWith("http")) {
                        link = "$homeUrl$link"
                    }

                    """{"vod_id":"${escapeJson(link.ifBlank { title })}","vod_name":"${escapeJson(title)}","vod_pic":"${escapeJson(img)}","vod_remarks":"${escapeJson(desc)}"}"""
                }

                """{"list":[${items.joinToString(",")}]}"""
            } catch (e: Exception) {
                """{"list":[],"error":"${escapeJson(e.message ?: "未知错误")}"}"""
            }
        }
    }

    /**
     * 获取首页内容
     */
    suspend fun homeContent(extUrl: String): String {
        return withContext(Dispatchers.IO) {
            try {
                val rules = loadRules(extUrl) ?: return@withContext """{"list":[],"class":[]}"""

                val homeUrl = rules["主页url"]?.jsonPrimitive?.contentOrNull ?: ""
                if (homeUrl.isBlank()) return@withContext """{"list":[],"class":[]}"""

                // 提取分类
                val categoryStr = rules["分类"]?.jsonPrimitive?.contentOrNull ?: ""
                val categories = categoryStr.split("#").mapNotNull { cat ->
                    val parts = cat.split("$")
                    if (parts.size >= 2) {
                        """{"type_name":"${escapeJson(parts[0])}","type_id":"${escapeJson(parts[1])}"}"""
                    } else null
                }

                val html = fetchHtml(homeUrl, rules) ?: return@withContext """{"class":[${categories.joinToString(",")}],"list":[]}"""

                val arrayRule = rules["数组"]?.jsonPrimitive?.contentOrNull ?: ""
                val titleRule = rules["标题"]?.jsonPrimitive?.contentOrNull ?: ""
                val linkRule = rules["链接"]?.jsonPrimitive?.contentOrNull ?: ""
                val imgRule = rules["图片"]?.jsonPrimitive?.contentOrNull ?: ""

                val blocks = splitByRule(html, arrayRule).take(20) // 首页最多20项
                val items = blocks.mapNotNull { block ->
                    val title = extractByRule(block, titleRule)
                    val link = extractByRule(block, linkRule)
                    val img = extractByRule(block, imgRule)
                    if (title.isBlank()) return@mapNotNull null
                    """{"vod_id":"${escapeJson(link.ifBlank { title })}","vod_name":"${escapeJson(title)}","vod_pic":"${escapeJson(img)}"}"""
                }

                """{"class":[${categories.joinToString(",")}],"list":[${items.joinToString(",")}]}"""
            } catch (e: Exception) {
                """{"list":[],"class":[]}"""
            }
        }
    }

    /**
     * 加载规则 JSON
     */
    private fun loadRules(url: String): JsonObject? {
        ruleCache[url]?.let { return it }

        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "okhttp/4.12.0")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val text = response.body?.string() ?: return null
                val obj = json.parseToJsonElement(text).jsonObject
                ruleCache[url] = obj
                obj
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 用"数组"规则分割 HTML
     * 规则格式: "前缀&&后缀" — 在 HTML 中找所有 [前缀...后缀] 的块
     */
    private fun splitByRule(html: String, rule: String): List<String> {
        if (rule.isBlank() || !rule.contains("&&")) return listOf(html)

        val parts = rule.split("&&", limit = 2)
        val prefix = parts[0]
        val suffix = parts[1]

        val result = mutableListOf<String>()
        var startIndex = 0

        while (true) {
            val begin = html.indexOf(prefix, startIndex)
            if (begin == -1) break

            val end = html.indexOf(suffix, begin + prefix.length)
            if (end == -1) break

            result.add(html.substring(begin, end + suffix.length))
            startIndex = end + suffix.length
        }

        return result
    }

    /**
     * 用规则从块中提取内容
     * 规则格式: "前缀&&后缀" — 取前缀和后缀之间的内容
     * 特殊: "*>" 表示匹配任意内容到 ">"
     */
    private fun extractByRule(block: String, rule: String): String {
        if (rule.isBlank() || !rule.contains("&&")) return ""

        val parts = rule.split("&&", limit = 2)
        var prefix = parts[0]
        val suffix = parts[1]

        // 处理通配符 *> : 表示前缀模糊匹配到 ">"
        if (prefix.contains("*>")) {
            val fixedPart = prefix.substringBefore("*>")
            val idx = block.indexOf(fixedPart)
            if (idx == -1) return ""
            val gtIdx = block.indexOf(">", idx + fixedPart.length)
            if (gtIdx == -1) return ""
            val afterGt = gtIdx + 1
            val endIdx = block.indexOf(suffix, afterGt)
            if (endIdx == -1) return ""
            return block.substring(afterGt, endIdx).trim()
        }

        val beginIdx = block.indexOf(prefix)
        if (beginIdx == -1) return ""

        val contentStart = beginIdx + prefix.length
        val endIdx = block.indexOf(suffix, contentStart)
        if (endIdx == -1) return ""

        return block.substring(contentStart, endIdx).trim()
    }

    private fun fetchHtml(url: String, rules: JsonObject): String? {
        return try {
            val ua = rules["ua"]?.jsonPrimitive?.contentOrNull
                ?: "Mozilla/5.0 (Linux; Android 11; Pixel 5) AppleWebKit/537.36"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", ua)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body?.string() else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun escapeJson(str: String): String {
        return str.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "")
    }
}
