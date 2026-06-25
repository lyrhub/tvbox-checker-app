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
 * 主界面 ViewModel
 * 管理源加载、测试、筛选、导出的业务逻辑
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val checker = SiteChecker()
    private val exportHelper = ExportHelper(application)

    // UI 状态
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    // 原始配置
    private var originalConfig: TvBoxConfig? = null

    /**
     * 加载 TVBox 源
     */
    fun loadSource(url: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, sourceUrl = url) }

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
                    _uiState.update { it.copy(isLoading = false, error = httpError) }
                    return@launch
                }
                val config = SourceParser.parse(text)
                originalConfig = config

                val results = config.sites.map { site ->
                    SiteCheckResult(site = site, status = CheckStatus.PENDING)
                }

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        results = results,
                        totalSites = config.sites.size,
                        checkedCount = 0,
                        aliveCount = 0,
                        deadCount = 0
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = "解析失败: ${e.message}") }
            }
        }
    }

    /**
     * 从文本内容加载（用于粘贴 JSON）
     */
    fun loadFromText(text: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val config = SourceParser.parse(text)
                originalConfig = config

                val results = config.sites.map { site ->
                    SiteCheckResult(site = site, status = CheckStatus.PENDING)
                }

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        results = results,
                        totalSites = config.sites.size,
                        checkedCount = 0,
                        aliveCount = 0,
                        deadCount = 0
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = "解析失败: ${e.message}") }
            }
        }
    }

    /**
     * 开始测试所有站点
     */
    fun startCheck() {
        val results = _uiState.value.results
        if (results.isEmpty()) return

        viewModelScope.launch {
            // 先把所有状态设为 TESTING
            _uiState.update {
                it.copy(
                    isTesting = true,
                    results = results.map { r -> r.copy(status = CheckStatus.TESTING) },
                    checkedCount = 0,
                    aliveCount = 0,
                    deadCount = 0
                )
            }

            val sites = results.map { it.site }
            checker.checkAll(sites).collect { (index, result) ->
                _uiState.update { state ->
                    val newResults = state.results.toMutableList()
                    // 根据测试结果决定是否默认选中
                    val selected = result.status == CheckStatus.OK
                    newResults[index] = result.copy(isSelected = selected)

                    val alive = newResults.count { it.status == CheckStatus.OK }
                    val dead = newResults.count { it.status == CheckStatus.FAILED || it.status == CheckStatus.TIMEOUT }
                    val checked = alive + dead

                    state.copy(
                        results = newResults,
                        checkedCount = checked,
                        aliveCount = alive,
                        deadCount = dead
                    )
                }
            }

            _uiState.update { it.copy(isTesting = false) }
        }
    }

    /**
     * 切换站点选中状态
     */
    fun toggleSite(index: Int) {
        _uiState.update { state ->
            val newResults = state.results.toMutableList()
            val current = newResults[index]
            newResults[index] = current.copy(isSelected = !current.isSelected)
            state.copy(results = newResults)
        }
    }

    /**
     * 全选/取消全选有效站点
     */
    fun selectAllAlive() {
        _uiState.update { state ->
            val newResults = state.results.map { r ->
                r.copy(isSelected = r.status == CheckStatus.OK)
            }
            state.copy(results = newResults)
        }
    }

    /**
     * 全选所有站点
     */
    fun selectAll() {
        _uiState.update { state ->
            state.copy(results = state.results.map { it.copy(isSelected = true) })
        }
    }

    /**
     * 取消全选
     */
    fun deselectAll() {
        _uiState.update { state ->
            state.copy(results = state.results.map { it.copy(isSelected = false) })
        }
    }

    /**
     * 生成导出 JSON
     */
    fun generateExportJson(): String {
        val config = originalConfig ?: return "{}"
        val selectedSites = _uiState.value.results
            .filter { it.isSelected }
            .map { it.site }
        return SourceParser.export(config, selectedSites)
    }

    /**
     * 保存到本地
     */
    fun saveToLocal(filename: String = "tvbox_source.json") {
        viewModelScope.launch {
            val json = generateExportJson()
            val result = exportHelper.saveToLocal(json, filename)
            _uiState.update { state ->
                result.fold(
                    onSuccess = { path -> state.copy(exportMessage = "已保存到: $path") },
                    onFailure = { e -> state.copy(exportMessage = "保存失败: ${e.message}") }
                )
            }
        }
    }

    /**
     * 上传到 GitHub Pages
     */
    fun uploadToGitHub(token: String, repo: String, path: String = "tvbox.json", branch: String = "gh-pages") {
        viewModelScope.launch {
            _uiState.update { it.copy(isExporting = true) }
            val json = generateExportJson()
            val result = exportHelper.uploadToGitHub(json, token, repo, path, branch)
            _uiState.update { state ->
                result.fold(
                    onSuccess = { url -> state.copy(isExporting = false, exportMessage = "已上传: $url") },
                    onFailure = { e -> state.copy(isExporting = false, exportMessage = "上传失败: ${e.message}") }
                )
            }
        }
    }

    /**
     * 上传到 Cloudflare Pages
     */
    fun uploadToCloudflare(token: String, accountId: String, projectName: String, filename: String = "tvbox.json") {
        viewModelScope.launch {
            _uiState.update { it.copy(isExporting = true) }
            val json = generateExportJson()
            val result = exportHelper.uploadToCloudflare(json, token, accountId, projectName, filename)
            _uiState.update { state ->
                result.fold(
                    onSuccess = { url -> state.copy(isExporting = false, exportMessage = "已上传: $url") },
                    onFailure = { e -> state.copy(isExporting = false, exportMessage = "上传失败: ${e.message}") }
                )
            }
        }
    }

    fun clearExportMessage() {
        _uiState.update { it.copy(exportMessage = null) }
    }

    override fun onCleared() {
        super.onCleared()
        checker.shutdown()
    }
}

/**
 * UI 状态
 */
data class UiState(
    val sourceUrl: String = "",
    val isLoading: Boolean = false,
    val isTesting: Boolean = false,
    val isExporting: Boolean = false,
    val error: String? = null,
    val results: List<SiteCheckResult> = emptyList(),
    val totalSites: Int = 0,
    val checkedCount: Int = 0,
    val aliveCount: Int = 0,
    val deadCount: Int = 0,
    val exportMessage: String? = null,
    val currentFilter: FilterType = FilterType.ALL
)

enum class FilterType {
    ALL, ALIVE, DEAD, SELECTED
}
