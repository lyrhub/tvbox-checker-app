package com.tvbox.checker.spider

import android.content.Context
import dalvik.system.DexClassLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Spider JAR/DEX 动态加载器
 *
 * TVBox 的 type 3 (csp_*) 站点通过 spider.jar 中的 Java 类来实现内容抓取。
 * 加载流程：
 * 1. 下载 spider.jar/dex 文件到本地缓存
 * 2. 使用 DexClassLoader 加载其中的类
 * 3. 通过反射调用 Spider 接口方法：
 *    - init(Context, String ext) -> 初始化
 *    - homeContent(boolean filter) -> 获取首页内容
 *    - categoryContent(String tid, String pg, boolean filter, HashMap extend) -> 分类内容
 *    - detailContent(List<String> ids) -> 详情（含播放链接）
 *    - searchContent(String key, boolean quick) -> 搜索
 *    - playerContent(String flag, String id, List<String> vipFlags) -> 播放地址
 *
 * Spider 接口的方法全部返回 String (JSON 格式)
 */
class SpiderLoader(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // 缓存已加载的 ClassLoader，避免重复下载
    private val loaderCache = ConcurrentHashMap<String, DexClassLoader>()
    // 缓存已实例化的 Spider 对象
    private val spiderCache = ConcurrentHashMap<String, Any>()

    private val dexDir: File by lazy {
        File(context.cacheDir, "spider_dex").apply { mkdirs() }
    }

    private val jarDir: File by lazy {
        File(context.cacheDir, "spider_jar").apply { mkdirs() }
    }

    /**
     * 获取 Spider 实例
     * @param spiderUrl spider jar/dex 的 URL（可能带 ;md5;xxx 后缀）
     * @param className 类名，如 "csp_Bilibili"
     * @param ext 站点的 ext 配置（传给 spider 的 init 方法）
     */
    suspend fun getSpider(spiderUrl: String, className: String, ext: String = ""): SpiderProxy? {
        val cacheKey = "$spiderUrl|$className"
        spiderCache[cacheKey]?.let { return SpiderProxy(it) }

        return withContext(Dispatchers.IO) {
            try {
                val classLoader = getOrCreateClassLoader(spiderUrl) ?: return@withContext null
                val spider = loadSpiderClass(classLoader, className, ext)
                if (spider != null) {
                    spiderCache[cacheKey] = spider
                    SpiderProxy(spider)
                } else null
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    /**
     * 获取或创建 DexClassLoader
     */
    private suspend fun getOrCreateClassLoader(spiderUrl: String): DexClassLoader? {
        val url = spiderUrl.split(";")[0] // 去掉 ;md5;xxx 后缀

        loaderCache[url]?.let { return it }

        // 下载 jar/dex 文件
        val jarFile = downloadSpider(url) ?: return null

        // 创建 DexClassLoader
        val loader = DexClassLoader(
            jarFile.absolutePath,
            dexDir.absolutePath,
            null,
            context.classLoader
        )

        loaderCache[url] = loader
        return loader
    }

    /**
     * 下载 spider 文件
     */
    private fun downloadSpider(url: String): File? {
        val fileName = url.substringAfterLast("/").substringBefore("?")
        val localFile = File(jarDir, fileName)

        // 如果本地缓存存在且不太旧（24小时内），直接用
        if (localFile.exists() && (System.currentTimeMillis() - localFile.lastModified()) < 24 * 3600 * 1000) {
            return localFile
        }

        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "TVBox-Checker/1.0")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body ?: return null
                localFile.outputStream().use { out ->
                    body.byteStream().copyTo(out)
                }
                localFile
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 从 ClassLoader 中加载指定的 Spider 类并初始化
     */
    private fun loadSpiderClass(classLoader: DexClassLoader, className: String, ext: String): Any? {
        // Spider 类通常在以下包路径之一：
        val possiblePaths = listOf(
            "com.github.catvod.spider.$className",
            "com.github.catvod.spider.${className.removePrefix("csp_")}",
            className  // 直接用类名（万一是完整路径）
        )

        for (path in possiblePaths) {
            try {
                val clazz = classLoader.loadClass(path)
                val instance = clazz.getDeclaredConstructor().newInstance()

                // 调用 init 方法
                try {
                    val initMethod = clazz.getMethod("init", Context::class.java, String::class.java)
                    initMethod.invoke(instance, context, ext)
                } catch (e: NoSuchMethodException) {
                    // 有些 spider 没有 init 方法，忽略
                }

                return instance
            } catch (e: ClassNotFoundException) {
                continue
            } catch (e: Exception) {
                e.printStackTrace()
                continue
            }
        }
        return null
    }

    /**
     * 清理所有缓存
     */
    fun clearCache() {
        spiderCache.clear()
        loaderCache.clear()
        jarDir.listFiles()?.forEach { it.delete() }
    }
}

/**
 * Spider 代理类 - 通过反射调用 Spider 接口的方法
 * 所有方法返回 JSON String
 */
class SpiderProxy(private val spider: Any) {

    private val clazz = spider::class.java

    /**
     * 搜索内容
     * 返回 JSON: { "list": [{ "vod_id": "xx", "vod_name": "xx", ... }] }
     */
    fun searchContent(keyword: String, quick: Boolean = false): String {
        return try {
            val method = clazz.getMethod("searchContent", String::class.java, Boolean::class.java)
            method.invoke(spider, keyword, quick) as? String ?: ""
        } catch (e: Exception) {
            // 有些 spider 的参数类型是 java.lang.Boolean（包装类型）
            try {
                val method = clazz.getMethod("searchContent", String::class.java, java.lang.Boolean::class.java)
                method.invoke(spider, keyword, java.lang.Boolean.valueOf(quick)) as? String ?: ""
            } catch (e2: Exception) {
                ""
            }
        }
    }

    /**
     * 获取首页内容
     * 返回 JSON: { "class": [...], "list": [...] }
     */
    fun homeContent(filter: Boolean = true): String {
        return try {
            val method = clazz.getMethod("homeContent", Boolean::class.java)
            method.invoke(spider, filter) as? String ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * 获取详情内容（含播放链接）
     * 返回 JSON: { "list": [{ "vod_play_from": "xx", "vod_play_url": "xx", ... }] }
     */
    fun detailContent(ids: List<String>): String {
        return try {
            val method = clazz.getMethod("detailContent", List::class.java)
            method.invoke(spider, ids) as? String ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * 获取播放地址
     * 返回 JSON: { "parse": 0/1, "url": "xxx", "header": {} }
     */
    fun playerContent(flag: String, id: String, vipFlags: List<String> = emptyList()): String {
        return try {
            val method = clazz.getMethod("playerContent", String::class.java, String::class.java, List::class.java)
            method.invoke(spider, flag, id, vipFlags) as? String ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * 分类内容
     */
    fun categoryContent(tid: String, pg: String, filter: Boolean, extend: HashMap<String, String> = hashMapOf()): String {
        return try {
            val method = clazz.getMethod("categoryContent", String::class.java, String::class.java, Boolean::class.java, HashMap::class.java)
            method.invoke(spider, tid, pg, filter, extend) as? String ?: ""
        } catch (e: Exception) {
            ""
        }
    }
}
