package com.tvbox.checker.spider

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.mozilla.javascript.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * DRPY JavaScript 引擎
 *
 * TVBox 的 DRPY 站点通过 JavaScript 脚本来抓取网页内容。
 * 工作流程：
 * 1. 加载 DRPY 引擎脚本 (drpy2.min.js) - 提供 pdfh/pdfa/pd 等解析辅助函数
 * 2. 加载站点规则脚本 (ext 字段的 .js 文件) - 定义 rule 对象
 * 3. 执行规则中的方法来获取内容
 *
 * rule 对象结构：
 * {
 *   title: "站点名",
 *   host: "https://example.com",
 *   url: "/category/fyclass",
 *   searchUrl: "/search?keyword=**",
 *   searchable: 1,
 *   headers: { "User-Agent": "..." },
 *   一级: "CSS选择器或JS代码",
 *   二级: { title: "...", desc: "...", content: "...", tabs: "...", lists: "..." },
 *   搜索: "CSS选择器或JS代码"
 * }
 *
 * 使用 Mozilla Rhino 作为 JS 引擎（Android 上最稳定的选择）
 */
class DrpyEngine(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // 缓存已下载的脚本内容
    private val scriptCache = ConcurrentHashMap<String, String>()

    private val cacheDir: File by lazy {
        File(context.cacheDir, "drpy_scripts").apply { mkdirs() }
    }

    /**
     * 执行 DRPY 搜索
     * @param engineUrl DRPY 引擎 URL (如 drpy2.min.js)
     * @param ruleUrl 规则脚本 URL (ext 字段)
     * @param keyword 搜索关键词
     * @return JSON 字符串: { "list": [{ "vod_id": "xx", "vod_name": "xx", ... }] }
     */
    suspend fun search(engineUrl: String, ruleUrl: String, keyword: String): String {
        return withContext(Dispatchers.IO) {
            try {
                val ruleScript = downloadScript(ruleUrl) ?: return@withContext ""
                val engineScript = downloadScript(engineUrl)

                executeSearch(engineScript, ruleScript, keyword)
            } catch (e: Exception) {
                e.printStackTrace()
                """{"list":[],"error":"${e.message?.replace("\"", "'")}"}"""
            }
        }
    }

    /**
     * 获取详情
     */
    suspend fun detail(engineUrl: String, ruleUrl: String, vodId: String): String {
        return withContext(Dispatchers.IO) {
            try {
                val ruleScript = downloadScript(ruleUrl) ?: return@withContext ""
                val engineScript = downloadScript(engineUrl)

                executeDetail(engineScript, ruleScript, vodId)
            } catch (e: Exception) {
                e.printStackTrace()
                """{"list":[],"error":"${e.message?.replace("\"", "'")}"}"""
            }
        }
    }

    /**
     * 执行搜索逻辑
     * 解析 rule 中的 host 和 searchUrl，构造请求并解析结果
     */
    private fun executeSearch(engineScript: String?, ruleScript: String, keyword: String): String {
        // 使用 Rhino 执行 JS
        val cx = Context.enter()
        cx.optimizationLevel = -1 // 解释模式，避免 Android dex 限制
        cx.languageVersion = Context.VERSION_ES6

        try {
            val scope = cx.initStandardObjects()

            // 注入网络请求能力
            injectFetchFunction(cx, scope)

            // 加载引擎脚本（如果有）
            if (!engineScript.isNullOrBlank()) {
                try {
                    cx.evaluateString(scope, engineScript, "drpy_engine.js", 1, null)
                } catch (e: Exception) {
                    // 引擎加载失败不要紧，有些规则是独立的
                }
            }

            // 加载规则脚本
            cx.evaluateString(scope, ruleScript, "rule.js", 1, null)

            // 获取 rule 对象
            val rule = scope.get("rule", scope)
            if (rule == Scriptable.NOT_FOUND || rule !is Scriptable) {
                return """{"list":[],"error":"未找到 rule 对象"}"""
            }

            // 提取 host 和 searchUrl
            val host = getProperty(rule, "host") ?: ""
            val searchUrl = getProperty(rule, "searchUrl") ?: ""

            if (host.isBlank() || searchUrl.isBlank()) {
                return """{"list":[],"error":"rule 缺少 host 或 searchUrl"}"""
            }

            // 构造搜索 URL
            val fullUrl = buildSearchUrl(host, searchUrl, keyword)

            // 请求搜索页面
            val html = fetchUrl(fullUrl, getHeaders(rule))
            if (html.isBlank()) {
                return """{"list":[],"error":"搜索页面请求失败"}"""
            }

            // 获取搜索解析规则
            val searchRule = rule.get("搜索", rule) ?: rule.get("search", rule)

            if (searchRule == null || searchRule == Scriptable.NOT_FOUND) {
                // 没有搜索规则，尝试用苹果CMS API格式解析
                return tryParseCmsJson(html)
            }

            // 如果搜索规则是字符串（CSS选择器），用简单解析
            // 如果是 JS 代码，在 Rhino 中执行
            val ruleStr = Context.toString(searchRule)
            if (ruleStr.startsWith("js:")) {
                // 执行 JS 解析代码
                val jsCode = ruleStr.removePrefix("js:")
                // 注入 input 变量（HTML 内容）
                scope.put("input", scope, html)
                scope.put("MY_URL", scope, fullUrl)
                val result = cx.evaluateString(scope, jsCode, "search_rule.js", 1, null)
                return Context.toString(result) ?: """{"list":[]}"""
            } else {
                // CSS 选择器模式 - 返回 host 信息让上层处理
                return """{"list":[],"host":"$host","note":"需要 HTML 解析器"}"""
            }
        } finally {
            Context.exit()
        }
    }

    /**
     * 执行详情获取
     */
    private fun executeDetail(engineScript: String?, ruleScript: String, vodId: String): String {
        val cx = Context.enter()
        cx.optimizationLevel = -1
        cx.languageVersion = Context.VERSION_ES6

        try {
            val scope = cx.initStandardObjects()
            injectFetchFunction(cx, scope)

            if (!engineScript.isNullOrBlank()) {
                try {
                    cx.evaluateString(scope, engineScript, "drpy_engine.js", 1, null)
                } catch (e: Exception) { /* ignore */ }
            }

            cx.evaluateString(scope, ruleScript, "rule.js", 1, null)

            val rule = scope.get("rule", scope)
            if (rule == Scriptable.NOT_FOUND || rule !is Scriptable) {
                return """{"list":[],"error":"未找到 rule 对象"}"""
            }

            val host = getProperty(rule, "host") ?: ""
            val detailUrl = if (vodId.startsWith("http")) vodId else "$host$vodId"

            val html = fetchUrl(detailUrl, getHeaders(rule))
            if (html.isBlank()) {
                return """{"list":[],"error":"详情页面请求失败"}"""
            }

            // 尝试作为 JSON 解析（CMS API 格式）
            return tryParseCmsJson(html)
        } finally {
            Context.exit()
        }
    }

    /**
     * 注入 fetch 函数供 JS 调用
     */
    private fun injectFetchFunction(cx: Context, scope: Scriptable) {
        val fetchFunc = object : BaseFunction() {
            override fun call(cx: Context?, scope: Scriptable?, thisObj: Scriptable?, args: Array<out Any>?): Any {
                val url = args?.getOrNull(0)?.toString() ?: return ""
                val headers = args?.getOrNull(1) as? Scriptable
                val headerMap = mutableMapOf<String, String>()
                if (headers != null) {
                    for (id in headers.ids) {
                        val key = id.toString()
                        headerMap[key] = headers.get(key, headers)?.toString() ?: ""
                    }
                }
                return fetchUrl(url, headerMap)
            }
        }
        scope.put("fetch", scope, fetchFunc)
        scope.put("request", scope, fetchFunc)
    }

    /**
     * 从 rule 中提取字符串属性
     */
    private fun getProperty(rule: Scriptable, name: String): String? {
        val value = rule.get(name, rule)
        if (value == Scriptable.NOT_FOUND || value == null) return null
        return Context.toString(value)
    }

    /**
     * 从 rule 中提取 headers
     */
    private fun getHeaders(rule: Scriptable): Map<String, String> {
        val headers = rule.get("headers", rule)
        val map = mutableMapOf<String, String>()
        if (headers is Scriptable && headers != Scriptable.NOT_FOUND) {
            for (id in headers.ids) {
                val key = id.toString()
                val value = headers.get(key, headers)?.toString() ?: ""
                // 替换常量
                map[key] = value
                    .replace("MOBILE_UA", "Mozilla/5.0 (Linux; Android 11; Pixel 5) AppleWebKit/537.36")
                    .replace("PC_UA", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            }
        }
        if (!map.containsKey("User-Agent")) {
            map["User-Agent"] = "Mozilla/5.0 (Linux; Android 11; Pixel 5) AppleWebKit/537.36"
        }
        return map
    }

    /**
     * 构建搜索 URL
     * searchUrl 格式: "/search?keyword=**" 或 "/search/wd/**.html"
     * ** 被替换为关键词
     */
    private fun buildSearchUrl(host: String, searchUrl: String, keyword: String): String {
        val encodedKeyword = java.net.URLEncoder.encode(keyword, "UTF-8")
        val path = searchUrl.replace("**", encodedKeyword)
            .replace("fypage", "1")
        return if (path.startsWith("http")) path else "$host$path"
    }

    /**
     * HTTP 请求
     */
    private fun fetchUrl(url: String, headers: Map<String, String> = emptyMap()): String {
        return try {
            val requestBuilder = Request.Builder().url(url)
            headers.forEach { (k, v) -> requestBuilder.header(k, v) }
            client.newCall(requestBuilder.build()).execute().use { response ->
                if (response.isSuccessful) response.body?.string() ?: "" else ""
            }
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * 下载脚本文件（带缓存）
     */
    private fun downloadScript(url: String): String? {
        if (url.isBlank()) return null
        scriptCache[url]?.let { return it }

        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "TVBox-Checker/1.0")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val content = response.body?.string() ?: ""
                    scriptCache[url] = content
                    content
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 尝试按苹果 CMS JSON 格式解析
     */
    private fun tryParseCmsJson(text: String): String {
        return try {
            // 如果内容是 JSON，直接返回
            val trimmed = text.trim()
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                trimmed
            } else {
                """{"list":[],"error":"非JSON内容"}"""
            }
        } catch (e: Exception) {
            """{"list":[],"error":"解析失败"}"""
        }
    }
}
