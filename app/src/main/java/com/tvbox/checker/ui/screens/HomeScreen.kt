package com.tvbox.checker.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tvbox.checker.data.CheckStatus
import com.tvbox.checker.data.SiteCheckResult
import com.tvbox.checker.ui.FilterType
import com.tvbox.checker.ui.UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    uiState: UiState,
    onLoadSource: (String) -> Unit,
    onStartCheck: () -> Unit,
    onToggleSite: (Int) -> Unit,
    onSelectAllAlive: () -> Unit,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
    onSaveLocal: () -> Unit,
    onShowExportDialog: () -> Unit,
    onFilterChange: (FilterType) -> Unit
) {
    var sourceUrl by remember { mutableStateOf(uiState.sourceUrl) }
    var showInputDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("TVBox 源检测器") },
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
            // 输入区域
            OutlinedTextField(
                value = sourceUrl,
                onValueChange = { sourceUrl = it },
                label = { Text("TVBox 源地址") },
                placeholder = { Text("输入 URL 或点击粘贴 JSON...") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 操作按钮行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { onLoadSource(sourceUrl) },
                    enabled = sourceUrl.isNotBlank() && !uiState.isLoading,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("加载源")
                }

                Button(
                    onClick = onStartCheck,
                    enabled = uiState.results.isNotEmpty() && !uiState.isTesting,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (uiState.isTesting) "测试中..." else "开始测试")
                }
            }

            // 统计信息
            if (uiState.results.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                StatsBar(uiState)

                // 测试进度
                if (uiState.isTesting) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { uiState.checkedCount.toFloat() / uiState.totalSites.coerceAtLeast(1) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 筛选和操作按钮
                FilterAndActionBar(
                    currentFilter = uiState.currentFilter,
                    onFilterChange = onFilterChange,
                    onSelectAllAlive = onSelectAllAlive,
                    onSelectAll = onSelectAll,
                    onDeselectAll = onDeselectAll,
                    hasResults = !uiState.isTesting && uiState.checkedCount > 0
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 站点列表
                val filteredResults = filterResults(uiState.results, uiState.currentFilter)
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    itemsIndexed(filteredResults) { _, (originalIndex, result) ->
                        SiteItem(
                            result = result,
                            onToggle = { onToggleSite(originalIndex) }
                        )
                    }
                }

                // 底部导出按钮
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val selectedCount = uiState.results.count { it.isSelected }
                    OutlinedButton(
                        onClick = onSaveLocal,
                        enabled = selectedCount > 0,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("保存本地 ($selectedCount)")
                    }
                    Button(
                        onClick = onShowExportDialog,
                        enabled = selectedCount > 0,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("上传云端")
                    }
                }
            }

            // 加载状态
            if (uiState.isLoading) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            // 错误信息
            uiState.error?.let { error ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(error, color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
            }
        }
    }

    // 导出消息 Snackbar 由外层处理
}

@Composable
private fun StatsBar(uiState: UiState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        StatChip("总计", uiState.totalSites.toString(), MaterialTheme.colorScheme.outline)
        StatChip("有效", uiState.aliveCount.toString(), Color(0xFF4CAF50))
        StatChip("无效", uiState.deadCount.toString(), Color(0xFFF44336))
        StatChip("已选", uiState.results.count { it.isSelected }.toString(), MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun StatChip(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = color)
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FilterAndActionBar(
    currentFilter: FilterType,
    onFilterChange: (FilterType) -> Unit,
    onSelectAllAlive: () -> Unit,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
    hasResults: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 筛选标签
        FilterChip(
            selected = currentFilter == FilterType.ALL,
            onClick = { onFilterChange(FilterType.ALL) },
            label = { Text("全部", fontSize = 12.sp) }
        )
        FilterChip(
            selected = currentFilter == FilterType.ALIVE,
            onClick = { onFilterChange(FilterType.ALIVE) },
            label = { Text("有效", fontSize = 12.sp) }
        )
        FilterChip(
            selected = currentFilter == FilterType.DEAD,
            onClick = { onFilterChange(FilterType.DEAD) },
            label = { Text("无效", fontSize = 12.sp) }
        )

        Spacer(modifier = Modifier.weight(1f))

        // 快捷操作
        if (hasResults) {
            TextButton(onClick = onSelectAllAlive) { Text("选有效", fontSize = 12.sp) }
            TextButton(onClick = onSelectAll) { Text("全选", fontSize = 12.sp) }
        }
    }
}

@Composable
private fun SiteItem(result: SiteCheckResult, onToggle: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (result.status) {
                CheckStatus.OK -> Color(0xFFE8F5E9)
                CheckStatus.FAILED, CheckStatus.TIMEOUT -> Color(0xFFFFEBEE)
                CheckStatus.TESTING -> Color(0xFFFFF8E1)
                CheckStatus.PENDING -> MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = result.isSelected,
                onCheckedChange = { onToggle() }
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = result.site.name.ifBlank { result.site.key },
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = buildString {
                        append("类型: ${result.site.type}")
                        if (result.site.api.isNotBlank()) {
                            append(" | ${result.site.api.take(30)}")
                        }
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // 状态指示
            Column(horizontalAlignment = Alignment.End) {
                when (result.status) {
                    CheckStatus.OK -> {
                        Text("✓", color = Color(0xFF4CAF50), fontWeight = FontWeight.Bold)
                        Text("${result.latency}ms", fontSize = 10.sp, color = Color(0xFF4CAF50))
                    }
                    CheckStatus.FAILED -> {
                        Text("✗", color = Color(0xFFF44336), fontWeight = FontWeight.Bold)
                        Text(
                            result.errorMsg.take(15),
                            fontSize = 10.sp,
                            color = Color(0xFFF44336)
                        )
                    }
                    CheckStatus.TIMEOUT -> {
                        Text("⏱", fontWeight = FontWeight.Bold)
                        Text("超时", fontSize = 10.sp, color = Color(0xFFFF9800))
                    }
                    CheckStatus.TESTING -> {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    }
                    CheckStatus.PENDING -> {
                        Text("—", color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        }
    }
}

/**
 * 根据筛选条件过滤结果，并保留原始索引
 */
private fun filterResults(
    results: List<SiteCheckResult>,
    filter: FilterType
): List<Pair<Int, SiteCheckResult>> {
    return results.mapIndexed { index, result -> index to result }
        .filter { (_, result) ->
            when (filter) {
                FilterType.ALL -> true
                FilterType.ALIVE -> result.status == CheckStatus.OK
                FilterType.DEAD -> result.status == CheckStatus.FAILED || result.status == CheckStatus.TIMEOUT
                FilterType.SELECTED -> result.isSelected
            }
        }
}
