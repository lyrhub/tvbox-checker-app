package com.tvbox.checker.data

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 导出/保存工具
 * 支持保存到本地、GitHub Pages、Cloudflare Pages
 */
class ExportHelper(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * 保存到本地 Downloads 目录
     */
    suspend fun saveToLocal(json: String, filename: String = "tvbox_source.json"): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS
                )
                if (!downloadsDir.exists()) downloadsDir.mkdirs()

                val file = File(downloadsDir, filename)
                file.writeText(json, Charsets.UTF_8)
                Result.success(file.absolutePath)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * 保存到内部存储（不需要权限）
     */
    suspend fun saveToInternal(json: String, filename: String = "tvbox_source.json"): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val file = File(context.filesDir, filename)
                file.writeText(json, Charsets.UTF_8)
                Result.success(file.absolutePath)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * 上传到 GitHub Pages（通过 GitHub API 推送文件到仓库）
     * @param token GitHub Personal Access Token
     * @param repo 仓库名如 "username/repo"
     * @param path 文件路径如 "tvbox.json"
     * @param branch 分支名，默认 "gh-pages"
     */
    suspend fun uploadToGitHub(
        json: String,
        token: String,
        repo: String,
        path: String = "tvbox.json",
        branch: String = "gh-pages"
    ): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val apiUrl = "https://api.github.com/repos/$repo/contents/$path"

                // 先获取文件的 sha（如果存在）
                val sha = getGitHubFileSha(apiUrl, token, branch)

                // Base64 编码内容
                val contentBase64 = android.util.Base64.encodeToString(
                    json.toByteArray(Charsets.UTF_8),
                    android.util.Base64.NO_WRAP
                )

                // 构建请求体
                val bodyJson = buildString {
                    append("{")
                    append("\"message\":\"Update TVBox source\",")
                    append("\"content\":\"$contentBase64\",")
                    append("\"branch\":\"$branch\"")
                    if (sha != null) {
                        append(",\"sha\":\"$sha\"")
                    }
                    append("}")
                }

                val request = Request.Builder()
                    .url(apiUrl)
                    .header("Authorization", "Bearer $token")
                    .header("Accept", "application/vnd.github+json")
                    .put(bodyJson.toRequestBody("application/json".toMediaType()))
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val pageUrl = "https://${repo.split("/")[0]}.github.io/${repo.split("/")[1]}/$path"
                        Result.success(pageUrl)
                    } else {
                        Result.failure(Exception("GitHub API: ${response.code} ${response.body?.string()}"))
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * 上传到 Cloudflare Pages（通过 Cloudflare API 进行 Direct Upload）
     * @param token Cloudflare API Token
     * @param accountId Cloudflare Account ID
     * @param projectName Pages 项目名
     */
    suspend fun uploadToCloudflare(
        json: String,
        token: String,
        accountId: String,
        projectName: String,
        filename: String = "tvbox.json"
    ): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                // 使用 KV 或 Direct Upload 方式
                // 这里用 Pages Direct Upload API
                val boundary = "----FormBoundary${System.currentTimeMillis()}"
                val body = buildString {
                    append("--$boundary\r\n")
                    append("Content-Disposition: form-data; name=\"/$filename\"; filename=\"$filename\"\r\n")
                    append("Content-Type: application/json\r\n\r\n")
                    append(json)
                    append("\r\n--$boundary--\r\n")
                }

                val request = Request.Builder()
                    .url("https://api.cloudflare.com/client/v4/accounts/$accountId/pages/projects/$projectName/deployments")
                    .header("Authorization", "Bearer $token")
                    .post(body.toRequestBody("multipart/form-data; boundary=$boundary".toMediaType()))
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val pageUrl = "https://$projectName.pages.dev/$filename"
                        Result.success(pageUrl)
                    } else {
                        Result.failure(Exception("Cloudflare API: ${response.code} ${response.body?.string()}"))
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    private fun getGitHubFileSha(apiUrl: String, token: String, branch: String): String? {
        return try {
            val request = Request.Builder()
                .url("$apiUrl?ref=$branch")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.github+json")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return null
                    // 简单提取 sha
                    val regex = """"sha"\s*:\s*"([a-f0-9]+)"""".toRegex()
                    regex.find(body)?.groupValues?.get(1)
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }
}
