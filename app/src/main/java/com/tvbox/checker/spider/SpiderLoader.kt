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
 * 注意事项：
 * - spider URL 可能以 .txt/.jar/.dex 结尾（CDN 伪装）
 * - 实际内容是 ZIP(JAR) 或 DEX 格式
 * - 下载后需要统一重命名为 .jar 供 DexClassLoader 识别
 * - 类名可能在多个包路径下
 */
class SpiderLoader(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val loaderCache = ConcurrentHashMap<String, DexClassLoader>()
    private val spiderCache = ConcurrentHashMap<String, Any>()

    private val dexDir: File by lazy {
        File(context.codeCacheDir, "spider_opt").apply { mkdirs() }
    }

    private val jarDir: File by lazy {
        File(context.cacheDir, "spider_jar").apply { mkdirs() }
    }

    /**
     * 获取 Spider 实例
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

    private suspend fun getOrCreateClassLoader(spiderUrl: String): DexClassLoader? {
        val url = spiderUrl.split(";")[0] // 去掉 ;md5;xxx 后缀
        loaderCache[url]?.let { return it }

        val jarFile = downloadSpider(url) ?: return null

        return try {
            val loader = DexClassLoader(
                jarFile.absolutePath,
                dexDir.absolutePath,
                null,
                context.classLoader
            )
            loaderCache[url] = loader
            loader
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 下载 spider 文件
     * 无论原始扩展名是什么(.txt/.jar/.dex)，统一保存为 .jar
     */
    private fun downloadSpider(url: String): File? {
        // 用 URL 的 hash 作为文件名，避免特殊字符问题
        val hash = url.hashCode().toUInt().toString(16)
        val localFile = File(jarDir, "spider_$hash.jar")

        // 缓存 12 小时
        if (localFile.exists() && localFile.length() > 1000 &&
            (System.currentTimeMillis() - localFile.lastModified()) < 12 * 3600 * 1000) {
            return localFile
        }

        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "okhttp/4.12.0")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return null
                }
                val body = response.body ?: return null
                localFile.outputStream().use { out ->
                    body.byteStream().copyTo(out)
                }
                // 验证文件大小合理（至少 1KB）
                if (localFile.length() < 1000) {
                    localFile.delete()
                    return null
                }
                localFile
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 加载 Spider 类 - 尝试多种包路径
     */
    private fun loadSpiderClass(classLoader: DexClassLoader, className: String, ext: String): Any? {
        val baseName = className.removePrefix("csp_").removePrefix("Csp_")

        // TVBox spider 类的可能路径
        val possiblePaths = listOf(
            "com.github.catvod.spider.$className",
            "com.github.catvod.spider.$baseName",
            "com.github.catvod.spider.${baseName.lowercase()}",
            // 有些 jar 用不同的包名
            "com.github.catvod.parser.$className",
            "com.github.catvod.parser.$baseName",
            className
        )

        for (path in possiblePaths) {
            try {
                val clazz = classLoader.loadClass(path)
                val instance = clazz.getDeclaredConstructor().newInstance()

                // 尝试多种 init 签名
                initSpider(clazz, instance, ext)

                return instance
            } catch (_: ClassNotFoundException) {
                continue
            } catch (_: NoClassDefFoundError) {
                continue
            } catch (e: Exception) {
                // 类找到了但实例化失败，记录但继续尝试
                e.printStackTrace()
                continue
            }
        }
        return null
    }

    /**
     * 初始化 Spider - 兼容多种 init 方法签名
     */
    private fun initSpider(clazz: Class<*>, instance: Any, ext: String) {
        // 签名 1: init(Context, String)
        try {
            val m = clazz.getMethod("init", Context::class.java, String::class.java)
            m.invoke(instance, context, ext)
            return
        } catch (_: NoSuchMethodException) {}

        // 签名 2: init(String)
        try {
            val m = clazz.getMethod("init", String::class.java)
            m.invoke(instance, ext)
            return
        } catch (_: NoSuchMethodException) {}

        // 签名 3: init(Context)
        try {
            val m = clazz.getMethod("init", Context::class.java)
            m.invoke(instance, context)
            return
        } catch (_: NoSuchMethodException) {}

        // 无 init 方法 - 正常
    }

    fun clearCache() {
        spiderCache.clear()
        loaderCache.clear()
        jarDir.listFiles()?.forEach { it.delete() }
    }
}

/**
 * Spider 代理类 - 通过反射调用接口方法
 */
class SpiderProxy(private val spider: Any) {

    private val clazz = spider::class.java

    fun searchContent(keyword: String, quick: Boolean = false): String {
        return invokeMethod("searchContent", arrayOf(String::class.java, Boolean::class.javaPrimitiveType!!), arrayOf(keyword, quick))
            ?: invokeMethod("searchContent", arrayOf(String::class.java, java.lang.Boolean::class.java), arrayOf(keyword, java.lang.Boolean.valueOf(quick)))
            ?: ""
    }

    fun homeContent(filter: Boolean = true): String {
        return invokeMethod("homeContent", arrayOf(Boolean::class.javaPrimitiveType!!), arrayOf(filter))
            ?: ""
    }

    fun detailContent(ids: List<String>): String {
        return invokeMethod("detailContent", arrayOf(List::class.java), arrayOf(ids))
            ?: ""
    }

    fun playerContent(flag: String, id: String, vipFlags: List<String> = emptyList()): String {
        return invokeMethod("playerContent", arrayOf(String::class.java, String::class.java, List::class.java), arrayOf(flag, id, vipFlags))
            ?: ""
    }

    fun categoryContent(tid: String, pg: String, filter: Boolean, extend: HashMap<String, String> = hashMapOf()): String {
        return invokeMethod("categoryContent", arrayOf(String::class.java, String::class.java, Boolean::class.javaPrimitiveType!!, HashMap::class.java), arrayOf(tid, pg, filter, extend))
            ?: ""
    }

    private fun invokeMethod(name: String, paramTypes: Array<Class<*>>, args: Array<Any>): String? {
        return try {
            val method = clazz.getMethod(name, *paramTypes)
            method.invoke(spider, *args) as? String
        } catch (_: Exception) {
            null
        }
    }
}
