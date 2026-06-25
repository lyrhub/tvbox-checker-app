package com.tvbox.checker.spider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.concurrent.TimeUnit

/**
 * XPath/CSS 选择器解析器
 *
 * 用于 type 4 站点和部分 type 3 站点的 HTML 解析。
 * TVBox 中 type 4 站点使用自定义规则来解析网页：
 * - 通过 CSS 选择器或 XPath 提取内容列表
 * - 规则定义在 ext 字段中（JSON 格式）
 *
 * ext 规则示例：
 * {
 *   "homeUrl": "https://example.com",
 *   "searchUrl": "https://example.com/search?wd=**",
 *   "listRule": { "list": ".module-items .module-item", "title": "a@title", "url": "a@href", "img": "img@data-src" },
 *   "detailRule": { "title": "h1@Text", "desc": ".data span@Text", "playList": ".module-play-list a" },
 *   "searchRule": { "list": ".module-items .module-item", "title": "a@title", "url": "a@href" }
 * }
 */
class XpathParser {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * 使用规则搜索
     * @param searchUrl 搜索URL模板 (** 替换为关键词)
     * @param keyword 搜索关键词
     * @param rules 解析规则
     * @return JSON 字符串
     */
    suspend fun search(searchUrl: String, keyword: String, rules: Map<String, String>): String {
        return withContext(Dispatchers.IO) {
            try {
                val encodedKeyword = java.net.URLEncoder.encode(keyword, "UTF-8")
                val url = searchUrl.replace("**", encodedKeyword).replace("fypage", "1")

                val html = fetchHtml(url) ?: return@withContext """{"list":[],"error":"请求失败"}"""
                val doc = Jsoup.parse(html, url)

                val listSelector = rules["list"] ?: return@withContext """{"list":[],"error":"缺少list规则"}"""
                val titleRule = rules["title"] ?: "a@title"
                val urlRule = rules["url"] ?: "a@href"
                val imgRule = rules["img"] ?: "img@src"
                val descRule = rules["desc"] ?: ""

                val elements = doc.select(listSelector)
                val items = elements.mapNotNull { element ->
                    val title = extractByRule(element, titleRule)
                    val vodUrl = extractByRule(element, urlRule)
                    val img = extractByRule(element, imgRule)
                    val desc = if (descRule.isNotBlank()) extractByRule(element, descRule) else ""

                    if (title.isNotBlank() && vodUrl.isNotBlank()) {
                        """{"vod_id":"${escapeJson(vodUrl)}","vod_name":"${escapeJson(title)}","vod_pic":"${escapeJson(img)}","vod_remarks":"${escapeJson(desc)}"}"""
                    } else null
                }

                """{"list":[${items.joinToString(",")}]}"""
            } catch (e: Exception) {
                """{"list":[],"error":"${escapeJson(e.message ?: "未知错误")}"}"""
            }
        }
    }

    /**
     * 获取详情
     */
    suspend fun detail(detailUrl: String, rules: Map<String, String>): String {
        return withContext(Dispatchers.IO) {
            try {
                val html = fetchHtml(detailUrl) ?: return@withContext """{"list":[],"error":"请求失败"}"""
                val doc = Jsoup.parse(html, detailUrl)

                val title = rules["title"]?.let { extractByRule(doc, it) } ?: ""
                val playListRule = rules["playList"] ?: ""

                if (playListRule.isBlank()) {
                    return@withContext """{"list":[{"vod_name":"${escapeJson(title)}","vod_play_from":"","vod_play_url":""}]}"""
                }

                val playElements = doc.select(playListRule)
                val episodes = playElements.mapNotNull { el ->
                    val name = el.text().trim()
                    val url = el.attr("abs:href")
                    if (name.isNotBlank() && url.isNotBlank()) "$name\$$url" else null
                }

                val playUrl = episodes.joinToString("#")
                """{"list":[{"vod_name":"${escapeJson(title)}","vod_play_from":"默认","vod_play_url":"$playUrl"}]}"""
            } catch (e: Exception) {
                """{"list":[],"error":"${escapeJson(e.message ?: "未知错误")}"}"""
            }
        }
    }

    /**
     * 根据规则从元素中提取内容
     * 规则格式: "选择器@属性" 或 "选择器@Text" 或 "选择器@Html"
     * 例如: "a@title", "img@src", "h1@Text", ".info span:eq(1)@Text"
     */
    private fun extractByRule(element: org.jsoup.nodes.Element, rule: String): String {
        if (rule.isBlank()) return ""

        val parts = rule.split("@", limit = 2)
        val selector = parts[0].trim()
        val attr = if (parts.size > 1) parts[1].trim() else "Text"

        val target = if (selector.isBlank()) element else {
            element.selectFirst(selector) ?: return ""
        }

        return when (attr.lowercase()) {
            "text" -> target.text().trim()
            "html" -> target.html().trim()
            "href" -> target.attr("abs:href")
            "src" -> target.attr("abs:src")
            else -> target.attr(attr)
        }
    }

    private fun extractByRule(doc: Document, rule: String): String {
        val parts = rule.split("@", limit = 2)
        val selector = parts[0].trim()
        val attr = if (parts.size > 1) parts[1].trim() else "Text"

        val target = doc.selectFirst(selector) ?: return ""
        return when (attr.lowercase()) {
            "text" -> target.text().trim()
            "html" -> target.html().trim()
            "href" -> target.attr("abs:href")
            "src" -> target.attr("abs:src")
            else -> target.attr(attr)
        }
    }

    private fun fetchHtml(url: String): String? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 11; Pixel 5) AppleWebKit/537.36")
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
