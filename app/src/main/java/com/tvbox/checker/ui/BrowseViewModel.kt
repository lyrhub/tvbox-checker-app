package com.tvbox.checker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tvbox.checker.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 浏览功能 ViewModel
 * 加载源后列出所有站点，选择后获取首页内容和分类
 */
class BrowseViewModel(application: Application) : AndroidViewModel(application) {

    private val siteApi = SiteApi(application)

    private val _browseState = MutableStateFlow(BrowseState())
    val browseState: StateFlow<BrowseState> = _browseState.asStateFlow()

    fun loadSource(url: String) {
        viewModelScope.launch {
            _browseState.update { it.copy(isLoading = true, error = null) }
            try {
                val (text, httpError) = withContext(Dispatchers.IO) {
                    val response = OkHttpClient.Builder()
                        .connectTimeout(15, TimeUnit.SECONDS)
                        .readTimeout(15, TimeUnit.SECONDS)
                        .build()
                        .newCall(Request.Builder().url(url.trim()).header("User-Agent", "TVBox-Checker/1.0").build())
                        .execute()
                    if (!response.isSuccessful) "" to "HTTP ${response.code}"
                    else (response.body?.string() ?: "") to null
                }

                if (httpError != null) {
                    _browseState.update { it.copy(isLoading = false, error = httpError) }
                    return@launch
                }

                val config = SourceParser.parse(text)
                if (config.spider.isNotBlank()) {
                    siteApi.globalSpiderUrl = config.spider
                }

                _browseState.update {
                    it.copy(isLoading = false, sourceLoaded = true, allSites = config.sites)
                }
            } catch (e: Exception) {
                _browseState.update { it.copy(isLoading = false, error = "加载失败: ${e.message}") }
            }
        }
    }

    fun selectSite(site: TvBoxSite) {
        // 如果已选择同一个站点，则返回站点列表
        if (_browseState.value.selectedSite?.key == site.key) {
            _browseState.update { it.copy(selectedSite = null, categories = emptyList(), contentItems = emptyList()) }
            return
        }

        _browseState.update { it.copy(selectedSite = site, isLoadingContent = true, categories = emptyList(), contentItems = emptyList()) }
        loadHomeContent(site)
    }

    fun selectCategory(categoryId: String) {
        val site = _browseState.value.selectedSite ?: return
        _browseState.update { it.copy(selectedCategory = categoryId, isLoadingContent = true) }
        loadCategoryContent(site, categoryId)
    }

    private fun loadHomeContent(site: TvBoxSite) {
        viewModelScope.launch {
            try {
                val result = siteApi.getHomeContent(site)
                _browseState.update { state ->
                    state.copy(
                        isLoadingContent = false,
                        categories = result.categories,
                        contentItems = result.items
                    )
                }
            } catch (e: Exception) {
                _browseState.update { it.copy(isLoadingContent = false) }
            }
        }
    }

    private fun loadCategoryContent(site: TvBoxSite, categoryId: String) {
        viewModelScope.launch {
            try {
                val result = siteApi.getCategoryContent(site, categoryId)
                _browseState.update { state ->
                    state.copy(isLoadingContent = false, contentItems = result)
                }
            } catch (e: Exception) {
                _browseState.update { it.copy(isLoadingContent = false) }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        siteApi.shutdown()
    }
}

data class BrowseState(
    val isLoading: Boolean = false,
    val sourceLoaded: Boolean = false,
    val error: String? = null,
    val allSites: List<TvBoxSite> = emptyList(),
    val selectedSite: TvBoxSite? = null,
    val isLoadingContent: Boolean = false,
    val categories: List<String> = emptyList(),
    val selectedCategory: String = "",
    val contentItems: List<SearchItem> = emptyList()
)
