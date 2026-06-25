package com.tvbox.checker.data

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 站点连通性测试器
 * 并发测试所有站点的 API 地址是否可连通
 */
class SiteChecker {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    // 控制并发数
    private val semaphore = Semaphore(20)

    /**
     * 测试所有站点的连通性
     * @return Flow 实时返回每个站点的测试结果
     */
    fun checkAll(sites: List<TvBoxSite>): Flow<Pair<Int, SiteCheckResult>> = channelFlow {
        val jobs = sites.mapIndexed { index, site ->
            launch {
                semaphore.withPermit {
                    val result = checkSite(site)
                    send(index to result)
                }
            }
        }
        jobs.forEach { it.join() }
    }

    /**
     * 测试单个站点
     */
    private suspend fun checkSite(site: TvBoxSite): SiteCheckResult {
        val url = getTestUrl(site)
        if (url.isBlank()) {
            return SiteCheckResult(
                site = site,
                status = CheckStatus.FAILED,
                errorMsg = "无有效测试地址"
            )
        }

        return withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "okhttp/4.12.0")
                    .build()

                client.newCall(request).execute().use { response ->
                    val latency = System.currentTimeMillis() - startTime
                    if (response.isSuccessful || response.code in 200..399) {
                        SiteCheckResult(
                            site = site,
                            status = CheckStatus.OK,
                            latency = latency
                        )
                    } else {
                        SiteCheckResult(
                            site = site,
                            status = CheckStatus.FAILED,
                            latency = latency,
                            errorMsg = "HTTP ${response.code}"
                        )
                    }
                }
            } catch (e: Exception) {
                val latency = System.currentTimeMillis() - startTime
                val status = if (latency >= 9500) CheckStatus.TIMEOUT else CheckStatus.FAILED
                SiteCheckResult(
                    site = site,
                    status = status,
                    latency = latency,
                    errorMsg = e.message ?: "Unknown error"
                )
            }
        }
    }

    /**
     * 从站点配置中提取可测试的 URL
     */
    private fun getTestUrl(site: TvBoxSite): String {
        val api = site.api
        if (api.isBlank()) return ""

        // 如果 api 是完整 URL，直接用
        if (api.startsWith("http://") || api.startsWith("https://")) {
            return api
        }

        // type 0/1 的 api 通常是 csp_Xxx 类名，不是 URL
        // type 3 的 api 通常是可测试的 URL
        if (site.type == 3 && api.startsWith("http")) {
            return api
        }

        // 尝试 ext 字段
        val ext = site.ext
        if (ext != null) {
            when {
                ext is JsonPrimitive && ext.isString -> {
                    val extStr = ext.content
                    if (extStr.startsWith("http://") || extStr.startsWith("https://")) {
                        return extStr
                    }
                }
                ext is JsonObject -> {
                    val url = ext["url"]
                    if (url is JsonPrimitive && url.isString && url.content.startsWith("http")) {
                        return url.content
                    }
                }
            }
        }

        return ""
    }

    fun shutdown() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
