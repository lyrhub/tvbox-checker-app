package com.tvbox.checker.spider

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Scriptable
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

// 类型别名避免 android.content.Context 和 org.mozilla.javascript.Context 冲突
private typealias RhinoContext = org.mozilla.javascript.Context

/**
 * DRPY JavaScript 引擎
 *
 * TVBox 的 DRPY 站点通过 JavaScript 脚本来抓取网页内容。
 * 使用 Mozilla Rhino 作为 JS 引擎（Android 上最稳定的选择）
 */
class DrpyEngine(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val scriptCache = ConcurrentHashMap<String, String>()

    private val cacheDir: File by lazy {
        File(context.cacheDir, "drpy_scripts").apply { mkdirs() }
    }

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

    private fun executeSearch(engineScript: String?, ruleScript: String, keyword: String): String {
        val cx = RhinoContext.enter()
        cx.optimizationLevel = -1
        cx.languageVersion = RhinoContext.VERSION_ES6

        try {
            val scope = cx.initStandardObjects()
            injectFetchFunction(cx, scope)

            if (!engineScript.isNullOrBlank()) {
                try {
                    cx.evaluateString(scope, engineScript, "drpy_engine.js", 1, null)
                } catch (_: Exception) {}
            }

            cx.evaluateString(scope, ruleScript, "rule.js", 1, null)

            val rule = scope.get("rule", scope)
            if (rule == Scriptable.NOT_FOUND || rule !is Scriptable) {
                return """{"list":[],"error":"未找到 rule 对象"}"""
            }

            val host = getProperty(rule, "host") ?: ""
            val searchUrl = getProperty(rule, "searchUrl") ?: ""

            if (host.isBlank() || searchUrl.isBlank()) {
                return """{"list":[],"error":"rule 缺少 host 或 searchUrl"}"""
            }

            val fullUrl = buildSearchUrl(host, searchUrl, keyword)
            val html = fetchUrl(fullUrl, getHeaders(rule))
            if (html.isBlank()) {
                return """{"list":[],"error":"搜索页面请求失败"}"""
            }

            val searchRule = rule.get("搜索", rule) ?: rule.get("search", rule)
            if (searchRule == null || searchRule == Scriptable.NOT_FOUND) {
                return tryParseCmsJson(html)
            }

            val ruleStr = RhinoContext.toString(searchRule)
            if (ruleStr.startsWith("js:")) {
                val jsCode = ruleStr.removePrefix("js:")
                scope.put("input", scope, html)
                scope.put("MY_URL", scope, fullUrl)
                val result = cx.evaluateString(scope, jsCode, "search_rule.js", 1, null)
                return RhinoContext.toString(result) ?: """{"list":[]}"""
            } else {
                return """{"list":[],"host":"$host","note":"需要 HTML 解析器"}"""
            }
        } finally {
            RhinoContext.exit()
        }
    }

    private fun executeDetail(engineScript: String?, ruleScript: String, vodId: String): String {
        val cx = RhinoContext.enter()
        cx.optimizationLevel = -1
        cx.languageVersion = RhinoContext.VERSION_ES6

        try {
            val scope = cx.initStandardObjects()
            injectFetchFunction(cx, scope)

            if (!engineScript.isNullOrBlank()) {
                try {
                    cx.evaluateString(scope, engineScript, "drpy_engine.js", 1, null)
                } catch (_: Exception) {}
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

            return tryParseCmsJson(html)
        } finally {
            RhinoContext.exit()
        }
    }

    private fun injectFetchFunction(cx: RhinoContext, scope: Scriptable) {
        val fetchFunc = object : BaseFunction() {
            override fun call(rhinoCx: org.mozilla.javascript.Context?, scope: Scriptable?, thisObj: Scriptable?, args: Array<out Any>?): Any {
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

    private fun getProperty(rule: Scriptable, name: String): String? {
        val value = rule.get(name, rule)
        if (value == Scriptable.NOT_FOUND || value == null) return null
        return RhinoContext.toString(value)
    }

    private fun getHeaders(rule: Scriptable): Map<String, String> {
        val headers = rule.get("headers", rule)
        val map = mutableMapOf<String, String>()
        if (headers is Scriptable && headers != Scriptable.NOT_FOUND) {
            for (id in headers.ids) {
                val key = id.toString()
                val value = headers.get(key, headers)?.toString() ?: ""
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

    private fun buildSearchUrl(host: String, searchUrl: String, keyword: String): String {
        val encodedKeyword = java.net.URLEncoder.encode(keyword, "UTF-8")
        val path = searchUrl.replace("**", encodedKeyword).replace("fypage", "1")
        return if (path.startsWith("http")) path else "$host$path"
    }

    private fun fetchUrl(url: String, headers: Map<String, String> = emptyMap()): String {
        return try {
            val requestBuilder = Request.Builder().url(url)
            headers.forEach { (k, v) -> requestBuilder.header(k, v) }
            client.newCall(requestBuilder.build()).execute().use { response ->
                if (response.isSuccessful) response.body?.string() ?: "" else ""
            }
        } catch (e: Exception) { "" }
    }

    private fun downloadScript(url: String): String? {
        if (url.isBlank()) return null
        scriptCache[url]?.let { return it }
        return try {
            val request = Request.Builder().url(url).header("User-Agent", "TVBox-Checker/1.0").build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val content = response.body?.string() ?: ""
                    scriptCache[url] = content
                    content
                } else null
            }
        } catch (e: Exception) { null }
    }

    private fun tryParseCmsJson(text: String): String {
        val trimmed = text.trim()
        return if (trimmed.startsWith("{") || trimmed.startsWith("[")) trimmed
        else """{"list":[],"error":"非JSON内容"}"""
    }
}
