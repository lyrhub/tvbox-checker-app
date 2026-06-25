package com.tvbox.checker.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 云端导出对话框
 * 支持上传到 GitHub Pages 或 Cloudflare Pages
 */
@Composable
fun ExportDialog(
    onDismiss: () -> Unit,
    onUploadGitHub: (token: String, repo: String, path: String, branch: String) -> Unit,
    onUploadCloudflare: (token: String, accountId: String, project: String, filename: String) -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("上传到云端") },
        text = {
            Column {
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }) {
                        Text("GitHub Pages", modifier = Modifier.padding(12.dp))
                    }
                    Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }) {
                        Text("Cloudflare", modifier = Modifier.padding(12.dp))
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                when (selectedTab) {
                    0 -> GitHubForm(onUpload = onUploadGitHub)
                    1 -> CloudflareForm(onUpload = onUploadCloudflare)
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun GitHubForm(
    onUpload: (token: String, repo: String, path: String, branch: String) -> Unit
) {
    var token by remember { mutableStateOf("") }
    var repo by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("tvbox.json") }
    var branch by remember { mutableStateOf("gh-pages") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("通过 GitHub API 上传文件到仓库", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("Personal Access Token") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = repo,
            onValueChange = { repo = it },
            label = { Text("仓库 (user/repo)") },
            placeholder = { Text("username/tvbox-source") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = path,
            onValueChange = { path = it },
            label = { Text("文件路径") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = branch,
            onValueChange = { branch = it },
            label = { Text("分支") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Button(
            onClick = { onUpload(token, repo, path, branch) },
            enabled = token.isNotBlank() && repo.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("上传到 GitHub Pages")
        }
    }
}

@Composable
private fun CloudflareForm(
    onUpload: (token: String, accountId: String, project: String, filename: String) -> Unit
) {
    var token by remember { mutableStateOf("") }
    var accountId by remember { mutableStateOf("") }
    var project by remember { mutableStateOf("") }
    var filename by remember { mutableStateOf("tvbox.json") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("通过 Cloudflare API 部署到 Pages 项目", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("API Token") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = accountId,
            onValueChange = { accountId = it },
            label = { Text("Account ID") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = project,
            onValueChange = { project = it },
            label = { Text("Pages 项目名") },
            placeholder = { Text("my-tvbox-source") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = filename,
            onValueChange = { filename = it },
            label = { Text("文件名") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Button(
            onClick = { onUpload(token, accountId, project, filename) },
            enabled = token.isNotBlank() && accountId.isNotBlank() && project.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("上传到 Cloudflare Pages")
        }
    }
}
