package com.tvbox.checker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tvbox.checker.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 搜索功能 ViewModel
 * 加载源后，搜索各站点的资源，并测试资源链接的可用性和速度
 */
class SearchViewModel(application: Application) : AndroidViewModel(application) {

    private val siteApi = SiteApi(application)

    private val _searchState = MutableStateFlow(SearchState())
    val searchState: StateFlow<SearchState> = _searchState.asStateFlow()

    private var loadedConfig: TvBoxConfig? = null
    private var loadedSites: List<TvBoxSite> = emptyList()

    /**
     * 加载源配置
     */
    fun loadSource(url: String) {
        viewModelScope.launch {
            _searchState.update { it.copy(isLoadingSource = true, loadError = null) }
            try {
                val (text, httpError) = withContext(Dispatchers.IO) {
                    val response = OkHttpClient.Builder()
                        .connectTimeout(15, TimeUnit.SECONDS)
                        .readTimeout(15, TimeUnit.SECONDS)
                        .build()
                        .newCall(
                            Request.Builder()
                                .url(url.trim())
                                .header("User-Agent", "TVBox-Checker/1.0")
                                .build()
                        ).execute()

                    if (!response.isSuccessful) {
                        "" to "HTTP ${response.code}"
                    } else {
                        (response.body?.string() ?: "") to null
                    }
                }

                if (httpError != null) {
                    _searchState.update { it.copy(isLoadingSource = false, loadError = httpError) }
                    return@launch
                }

                val config = SourceParser.parse(text)
                loadedConfig = config

                // 设置全局 spider URL
                if (config.spider.isNotBlank()) {
                    siteApi.globalSpiderUrl = config.spider
                }

                // 保留所有可搜索的站点 (type 0, 1, 3, 4 且 searchable != 0)
                loadedSites = config.sites.filter { site ->
                    (site.type == 0 || site.type == 1 || site.type == 3 || site.type == 4) &&
                    site.searchable != 0 &&
                    (site.api.isNotBlank() || site.ext != null)
                }

                _searchState.update {
                    it.copy(
                        isLoadingSource = false,
                        sourceLoaded = true,
                        totalSites = loadedSites.size,
                        allSites = config.sites,
                        searchableSites = loadedSites
                    )
                }
            } catch (e: Exception) {
                _searchState.update { it.copy(isLoadingSource = false, loadError = "加载失败: ${e.message}") }
            }
        }
    }

    /**
     * 在所有可搜索站点中搜索关键词
     */
    fun search(keyword: String) {
        if (keyword.isBlank() || loadedSites.isEmpty()) return

        viewModelScope.launch {
            _searchState.update {
                it.copy(
                    isSearching = true,
                    keyword = keyword,
                    searchResults = emptyList(),
                    searchedCount = 0,
                    resultCount = 0
                )
            }

            val semaphore = Semaphore(10) // 并发10个
            val results = mutableListOf<SearchResult>()

            val jobs = loadedSites.map { site ->
                viewModelScope.launch {
                    semaphore.withPermit {
                        val result = siteApi.search(site, keyword)
                        synchronized(results) {
                            results.add(result)
                        }
                        _searchState.update { state ->
                            val allResults = synchronized(results) { results.toList() }
                            state.copy(
                                searchResults = allResults.sortedByDescending { it.items.size },
                                searchedCount = allResults.size,
                                resultCount = allResults.sumOf { it.items.size }
                            )
                        }
                    }
                }
            }

            jobs.forEach { it.join() }
            _searchState.update { it.copy(isSearching = false) }
        }
    }

    /**
     * 获取资源详情（播放链接）
     */
    fun getDetail(site: TvBoxSite, vodId: String) {
        viewModelScope.launch {
            _searchState.update { it.copy(isLoadingDetail = true, currentDetail = null) }
            val result = siteApi.getDetail(site, vodId)
            _searchState.update { it.copy(isLoadingDetail = false, currentDetail = result) }
        }
    }

    /**
     * 测试播放链接列表
     */
    fun testPlayUrls(urls: List<String>) {
        if (urls.isEmpty()) {
            // 空列表 = 清除测试结果（关闭面板）
            _searchState.update { it.copy(playTestResults = emptyList(), testedUrlCount = 0, isTesting = false) }
            return
        }

        viewModelScope.launch {
            _searchState.update {
                it.copy(
                    isTesting = true,
                    playTestResults = emptyList(),
                    testedUrlCount = 0
                )
            }

            val semaphore = Semaphore(5) // 并发5个测试
            val results = mutableListOf<PlayTestResult>()

            val jobs = urls.map { url ->
                viewModelScope.launch {
                    semaphore.withPermit {
                        val result = siteApi.testPlayUrl(url)
                        synchronized(results) {
                            results.add(result)
                        }
                        _searchState.update { state ->
                            val allResults = synchronized(results) { results.toList() }
                            state.copy(
                                playTestResults = allResults.sortedBy { it.latency },
                                testedUrlCount = allResults.size
                            )
                        }
                    }
                }
            }

            jobs.forEach { it.join() }
            _searchState.update { it.copy(isTesting = false) }
        }
    }

    /**
     * 测试某个播放源的所有集数链接
     */
    fun testPlaySource(playSource: PlaySource) {
        val urls = playSource.episodes.map { it.url }.filter { it.startsWith("http") }
        // 最多测试前10个
        testPlayUrls(urls.take(10))
    }

    override fun onCleared() {
        super.onCleared()
        siteApi.shutdown()
    }
}

data class SearchState(
    // 源加载
    val isLoadingSource: Boolean = false,
    val sourceLoaded: Boolean = false,
    val loadError: String? = null,
    val totalSites: Int = 0,
    val allSites: List<TvBoxSite> = emptyList(),
    val searchableSites: List<TvBoxSite> = emptyList(),

    // 搜索
    val keyword: String = "",
    val isSearching: Boolean = false,
    val searchResults: List<SearchResult> = emptyList(),
    val searchedCount: Int = 0,
    val resultCount: Int = 0,

    // 详情
    val isLoadingDetail: Boolean = false,
    val currentDetail: DetailResult? = null,

    // 播放测试
    val isTesting: Boolean = false,
    val playTestResults: List<PlayTestResult> = emptyList(),
    val testedUrlCount: Int = 0
)
