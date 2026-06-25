package com.tvbox.checker

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tvbox.checker.ui.FilterType
import com.tvbox.checker.ui.MainViewModel
import com.tvbox.checker.ui.screens.ExportDialog
import com.tvbox.checker.ui.screens.HomeScreen
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
                    MainContent()
                }
            }
        }
    }

    @Composable
    private fun MainContent() {
        val viewModel: MainViewModel = viewModel()
        val uiState by viewModel.uiState.collectAsStateWithLifecycle()
        var showExportDialog by remember { mutableStateOf(false) }

        // 显示导出消息
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
            onFilterChange = { /* TODO: implement filter state */ }
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
}
