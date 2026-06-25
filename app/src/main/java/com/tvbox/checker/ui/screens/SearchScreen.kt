package com.tvbox.checker.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tvbox.checker.data.*
import com.tvbox.checker.ui.SearchState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    searchState: SearchState,
    onLoadSource: (String) -> Unit,
    onSearch: (String) -> Unit,
    onGetDetail: (TvBoxSite, String) -> Unit,
    onTestPlaySource: (PlaySource) -> Unit,
    onTestPlayUrls: (List<String>) -> Unit
) {
    var sourceUrl by remember { mutableStateOf("") }
    var searchKeyword by remember { mutableStateOf("") }
    var showDetail by remember { mutableStateOf(false) }
    var selectedSite by remember { mutableStateOf<TvBoxSite?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("资源搜索与测试") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // 源输入区域（如果尚未加载）
            if (!searchState.sourceLoaded) {
                OutlinedTextField(
                    value = sourceUrl,
                    onValueChange = { sourceUrl = it },
                    label = { Text("TVBox 源地址") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { onLoadSource(sourceUrl) },
                    enabled = sourceUrl.isNotBlank() && !searchState.isLoadingSource,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (searchState.isLoadingSource) "加载中..." else "加载源")
                }

                searchState.loadError?.let { error ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(error, color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
                }
            } else {
                // 已加载源 - 显示搜索区域
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("已加载源", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                "共 ${searchState.allSites.size} 个站点，${searchState.totalSites} 个可搜索",
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 搜索框
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = searchKeyword,
                        onValueChange = { searchKeyword = it },
                        label = { Text("搜索关键词") },
                        placeholder = { Text("输入影视名称...") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    Button(
                        onClick = { onSearch(searchKeyword) },
                        enabled = searchKeyword.isNotBlank() && !searchState.isSearching
                    ) {
                        Text("搜索")
                    }
                }

                // 搜索进度
                if (searchState.isSearching) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { searchState.searchedCount.toFloat() / searchState.totalSites.coerceAtLeast(1) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "已搜索 ${searchState.searchedCount}/${searchState.totalSites} 站点，找到 ${searchState.resultCount} 条结果",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 搜索结果
                if (searchState.searchResults.isNotEmpty()) {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(searchState.searchResults.filter { it.items.isNotEmpty() }) { result ->
                            SearchResultCard(
                                result = result,
                                onItemClick = { item ->
                                    val site = searchState.searchableSites.find { it.key == result.siteKey }
                                    if (site != null) {
                                        selectedSite = site
                                        onGetDetail(site, item.vodId)
                                        showDetail = true
                                    }
                                }
                            )
                        }

                        // 显示搜索失败的站点
                        val failedSites = searchState.searchResults.filter { it.error != null }
                        if (failedSites.isNotEmpty()) {
                            item {
                                FailedSitesSection(failedSites)
                            }
                        }
                    }
                }

                // 播放链接测试结果
                if (searchState.playTestResults.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    PlayTestResultsSection(
                        results = searchState.playTestResults,
                        isTesting = searchState.isTesting,
                        testedCount = searchState.testedUrlCount,
                        onDismiss = { onTestPlayUrls(emptyList()) } // 清空结果来关闭
                    )
                }
            }
        }
    }

    // 详情弹窗
    if (showDetail && searchState.currentDetail != null) {
        DetailDialog(
            detail = searchState.currentDetail!!,
            isLoading = searchState.isLoadingDetail,
            onDismiss = { showDetail = false },
            onTestSource = { playSource -> onTestPlaySource(playSource) }
        )
    }
}

@Composable
private fun SearchResultCard(
    result: SearchResult,
    onItemClick: (SearchItem) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    result.siteName,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "${result.items.size} 条 · ${result.latency}ms",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            result.items.take(5).forEach { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onItemClick(item) }
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            item.vodName,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (item.typeName.isNotBlank() || item.vodRemarks.isNotBlank()) {
                            Text(
                                listOfNotNull(
                                    item.typeName.takeIf { it.isNotBlank() },
                                    item.vodRemarks.takeIf { it.isNotBlank() },
                                    item.vodYear.takeIf { it.isNotBlank() }
                                ).joinToString(" · "),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Text("▶", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                }
            }

            if (result.items.size > 5) {
                Text(
                    "还有 ${result.items.size - 5} 条结果...",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun FailedSitesSection(failedSites: List<SearchResult>) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "搜索失败的站点 (${failedSites.size})",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.error
                )
                Text(if (expanded) "收起" else "展开", fontSize = 12.sp)
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(4.dp))
                failedSites.forEach { site ->
                    Text(
                        "${site.siteName}: ${site.error}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailDialog(
    detail: DetailResult,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onTestSource: (PlaySource) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(detail.vodName.ifBlank { "资源详情" }) },
        text = {
            if (isLoading) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (detail.error != null) {
                Text("获取详情失败: ${detail.error}", color = MaterialTheme.colorScheme.error)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(detail.playSources) { source ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "📺 ${source.name} (${source.episodes.size}集)",
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 13.sp
                                    )
                                    TextButton(onClick = { onTestSource(source) }) {
                                        Text("测速", fontSize = 12.sp)
                                    }
                                }
                                // 显示前几集链接
                                source.episodes.take(3).forEach { ep ->
                                    Text(
                                        "${ep.name}: ${ep.url.take(50)}...",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                                if (source.episodes.size > 3) {
                                    Text(
                                        "...共 ${source.episodes.size} 集",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

@Composable
private fun PlayTestResultsSection(
    results: List<PlayTestResult>,
    isTesting: Boolean,
    testedCount: Int,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("播放链接测速", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isTesting) {
                        Text("测试中 $testedCount...", fontSize = 12.sp)
                    }
                    TextButton(onClick = onDismiss) {
                        Text("✕ 关闭", fontSize = 12.sp)
                    }
                }
            }

            if (isTesting) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
            }

            Spacer(modifier = Modifier.height(4.dp))

            results.forEach { result ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val statusIcon = when (result.status) {
                        PlayStatus.OK -> "✓"
                        PlayStatus.FAILED -> "✗"
                        PlayStatus.TIMEOUT -> "⏱"
                        PlayStatus.INVALID -> "⚠"
                    }
                    val statusColor = when (result.status) {
                        PlayStatus.OK -> Color(0xFF4CAF50)
                        PlayStatus.FAILED -> Color(0xFFF44336)
                        PlayStatus.TIMEOUT -> Color(0xFFFF9800)
                        PlayStatus.INVALID -> Color(0xFFFF9800)
                    }

                    Text(statusIcon, color = statusColor, fontSize = 14.sp)
                    Text(
                        result.url.takeLast(40),
                        fontSize = 11.sp,
                        modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${result.latency}ms",
                        fontSize = 11.sp,
                        color = when {
                            result.latency < 500 -> Color(0xFF4CAF50)
                            result.latency < 2000 -> Color(0xFFFF9800)
                            else -> Color(0xFFF44336)
                        }
                    )
                }
            }
        }
    }
}
