package com.tvbox.checker.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tvbox.checker.data.TvBoxSite
import com.tvbox.checker.ui.BrowseState

/**
 * 浏览页面 - 对不可搜索的站点，展示其分类和内容
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
    browseState: BrowseState,
    onLoadSource: (String) -> Unit,
    onSelectSite: (TvBoxSite) -> Unit,
    onSelectCategory: (String) -> Unit
) {
    var sourceUrl by remember { mutableStateOf("") }

    // 返回键处理：在站点详情时返回站点列表，而不是退出
    if (browseState.selectedSite != null) {
        BackHandler {
            onSelectSite(browseState.selectedSite) // 触发取消选择
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (browseState.selectedSite != null) {
                        Text(browseState.selectedSite.name.ifBlank { browseState.selectedSite.key })
                    } else {
                        Text("分类浏览")
                    }
                },
                navigationIcon = {
                    if (browseState.selectedSite != null) {
                        TextButton(onClick = { onSelectSite(browseState.selectedSite) }) {
                            Text("← 返回", fontSize = 14.sp)
                        }
                    }
                },
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
            if (!browseState.sourceLoaded) {
                // 源输入
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
                    enabled = sourceUrl.isNotBlank() && !browseState.isLoading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (browseState.isLoading) "加载中..." else "加载源")
                }
                browseState.error?.let { error ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(error, color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
                }
            } else if (browseState.selectedSite == null) {
                // 站点列表
                Text(
                    "全部站点 (${browseState.allSites.size}个)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Spacer(modifier = Modifier.height(8.dp))

                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(browseState.allSites) { site ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectSite(site) }
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        site.name.ifBlank { site.key },
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        "类型: ${site.type} | ${if (site.searchable != 0) "可搜索" else "仅浏览"}",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text("▶", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            } else {
                // 已选择站点 - 显示分类和内容

                if (browseState.isLoadingContent) {
                    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    // 分类标签
                    if (browseState.categories.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            browseState.categories.take(8).forEach { cat ->
                                FilterChip(
                                    selected = cat == browseState.selectedCategory,
                                    onClick = { onSelectCategory(cat) },
                                    label = { Text(cat, fontSize = 12.sp) }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // 内容列表
                    if (browseState.contentItems.isNotEmpty()) {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(browseState.contentItems) { item ->
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Row(modifier = Modifier.padding(10.dp)) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(item.vodName, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                            if (item.typeName.isNotBlank() || item.vodRemarks.isNotBlank()) {
                                                Text(
                                                    listOfNotNull(item.typeName.takeIf { it.isNotBlank() }, item.vodRemarks.takeIf { it.isNotBlank() }).joinToString(" · "),
                                                    fontSize = 11.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else if (!browseState.isLoadingContent) {
                        Text("暂无内容", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
