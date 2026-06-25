package com.tvbox.checker

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tvbox.checker.ui.FilterType
import com.tvbox.checker.ui.MainViewModel
import com.tvbox.checker.ui.SearchViewModel
import com.tvbox.checker.ui.screens.ExportDialog
import com.tvbox.checker.ui.screens.HomeScreen
import com.tvbox.checker.ui.screens.SearchScreen
import com.tvbox.checker.ui.theme.TVBoxCheckerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TVBoxCheckerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainApp()
                }
            }
        }
    }

    @Composable
    private fun MainApp() {
        var currentTab by remember { mutableIntStateOf(0) }

        Scaffold(
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = currentTab == 0,
                        onClick = { currentTab = 0 },
                        icon = { Text("🔍") },
                        label = { Text("源检测") }
                    )
                    NavigationBarItem(
                        selected = currentTab == 1,
                        onClick = { currentTab = 1 },
                        icon = { Text("🎬") },
                        label = { Text("搜索测试") }
                    )
                }
            }
        ) { padding ->
            Box(modifier = Modifier.padding(padding)) {
                when (currentTab) {
                    0 -> CheckerTab()
                    1 -> SearchTab()
                }
            }
        }
    }

    @Composable
    private fun CheckerTab() {
        val viewModel: MainViewModel = viewModel()
        val uiState by viewModel.uiState.collectAsStateWithLifecycle()
        var showExportDialog by remember { mutableStateOf(false) }

        LaunchedEffect(uiState.exportMessage) {
            uiState.exportMessage?.let { msg ->
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                viewModel.clearExportMessage()
            }
        }

        HomeScreen(
            uiState = uiState,
            onLoadSource = { url -> viewModel.loadSource(url) },
            onStartCheck = { viewModel.startCheck() },
            onToggleSite = { index -> viewModel.toggleSite(index) },
            onSelectAllAlive = { viewModel.selectAllAlive() },
            onSelectAll = { viewModel.selectAll() },
            onDeselectAll = { viewModel.deselectAll() },
            onSaveLocal = { viewModel.saveToLocal() },
            onShowExportDialog = { showExportDialog = true },
            onFilterChange = { /* filter state managed in UI */ }
        )

        if (showExportDialog) {
            ExportDialog(
                onDismiss = { showExportDialog = false },
                onUploadGitHub = { token, repo, path, branch ->
                    viewModel.uploadToGitHub(token, repo, path, branch)
                    showExportDialog = false
                },
                onUploadCloudflare = { token, accountId, project, filename ->
                    viewModel.uploadToCloudflare(token, accountId, project, filename)
                    showExportDialog = false
                }
            )
        }
    }

    @Composable
    private fun SearchTab() {
        val viewModel: SearchViewModel = viewModel()
        val searchState by viewModel.searchState.collectAsStateWithLifecycle()

        SearchScreen(
            searchState = searchState,
            onLoadSource = { url -> viewModel.loadSource(url) },
            onSearch = { keyword -> viewModel.search(keyword) },
            onGetDetail = { site, vodId -> viewModel.getDetail(site, vodId) },
            onTestPlaySource = { source -> viewModel.testPlaySource(source) },
            onTestPlayUrls = { urls -> viewModel.testPlayUrls(urls) }
        )
    }
}
