package com.pinpulse.radar.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.pinpulse.radar.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class UpdateState(
    val isChecking: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadProgress: Float = 0f,
    val updateAvailable: Boolean = false,
    val latestVersion: String = "",
    val currentVersion: String = BuildConfig.VERSION_NAME,
    val apkUrl: String = "",
    val releaseNotes: String = "",
    val statusMessage: String? = null,
    val errorMessage: String? = null
)

object UpdateManager {
    private const val TAG = "UpdateManager"
    private const val GITHUB_REPO = "DALI951/pinpulse"
    private const val RELEASES_API = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"

    private val _updateState = MutableStateFlow(UpdateState())
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    suspend fun checkForUpdates(): UpdateState = withContext(Dispatchers.IO) {
        _updateState.value = _updateState.value.copy(
            isChecking = true,
            statusMessage = "Checking for updates...",
            errorMessage = null
        )
        try {
            val url = URL(RELEASES_API)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("User-Agent", "PinPulse-Android")
                setRequestProperty("Accept", "application/vnd.github.v3+json")
            }

            if (conn.responseCode == 200) {
                val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(jsonStr)
                val tagName = root.optString("tag_name", "").trim()
                val body = root.optString("body", "")

                // Find APK asset
                var apkUrl = ""
                val assets = root.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name", "")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            apkUrl = asset.optString("browser_download_url", "")
                            break
                        }
                    }
                }

                val currentVer = BuildConfig.VERSION_NAME
                val isNewer = compareVersions(tagName, currentVer)

                val newState = _updateState.value.copy(
                    isChecking = false,
                    updateAvailable = isNewer && apkUrl.isNotBlank(),
                    latestVersion = tagName.removePrefix("v"),
                    currentVersion = currentVer,
                    apkUrl = apkUrl,
                    releaseNotes = body,
                    statusMessage = if (isNewer && apkUrl.isNotBlank()) {
                        "Update available: $tagName"
                    } else {
                        "You have the latest version ($currentVer)"
                    }
                )
                _updateState.value = newState
                newState
            } else {
                val newState = _updateState.value.copy(
                    isChecking = false,
                    errorMessage = "GitHub API returned code ${conn.responseCode}",
                    statusMessage = null
                )
                _updateState.value = newState
                newState
            }
        } catch (e: Exception) {
            Log.e(TAG, "Check update failed: ${e.message}")
            val newState = _updateState.value.copy(
                isChecking = false,
                errorMessage = "Failed to check update: ${e.message}",
                statusMessage = null
            )
            _updateState.value = newState
            newState
        }
    }

    private fun compareVersions(latestTag: String, currentVer: String): Boolean {
        try {
            val cleanLatest = latestTag.removePrefix("v").trim()
            val cleanCurrent = currentVer.removePrefix("v").trim()
            if (cleanLatest == cleanCurrent) return false

            val latestParts = cleanLatest.split(".").mapNotNull { it.toIntOrNull() }
            val currentParts = cleanCurrent.split(".").mapNotNull { it.toIntOrNull() }

            val maxLen = maxOf(latestParts.size, currentParts.size)
            for (i in 0 until maxLen) {
                val l = latestParts.getOrElse(i) { 0 }
                val c = currentParts.getOrElse(i) { 0 }
                if (l > c) return true
                if (l < c) return false
            }
        } catch (e: Exception) {}
        return false
    }

    suspend fun downloadAndInstall(context: Context, onInstalled: () -> Unit = {}) = withContext(Dispatchers.IO) {
        val apkUrl = _updateState.value.apkUrl
        if (apkUrl.isBlank()) return@withContext

        _updateState.value = _updateState.value.copy(
            isDownloading = true,
            downloadProgress = 0f,
            statusMessage = "Downloading update..."
        )

        try {
            var currentUrl = apkUrl
            var conn: HttpURLConnection
            var redirects = 0
            while (true) {
                conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 15000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "PinPulse-Android")
                }
                val code = conn.responseCode
                if (code == HttpURLConnection.HTTP_MOVED_PERM || code == HttpURLConnection.HTTP_MOVED_TEMP || code == 307 || code == 308) {
                    currentUrl = conn.getHeaderField("Location") ?: break
                    redirects++
                    if (redirects > 5) break
                } else {
                    break
                }
            }

            val contentLength = conn.contentLength.toLong()
            val destinationFile = File(context.cacheDir, "pinpulse_update.apk")
            if (destinationFile.exists()) destinationFile.delete()

            conn.inputStream.use { input ->
                FileOutputStream(destinationFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalRead: Long = 0

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        if (contentLength > 0) {
                            val progress = (totalRead.toFloat() / contentLength).coerceIn(0f, 1f)
                            _updateState.value = _updateState.value.copy(downloadProgress = progress)
                        }
                    }
                }
            }

            _updateState.value = _updateState.value.copy(
                isDownloading = false,
                downloadProgress = 1f,
                statusMessage = "Download complete! Launching installer..."
            )

            withContext(Dispatchers.Main) {
                installApk(context, destinationFile)
                onInstalled()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download APK failed: ${e.message}")
            _updateState.value = _updateState.value.copy(
                isDownloading = false,
                errorMessage = "Download failed: ${e.message}"
            )
        }
    }

    fun installApk(context: Context, file: File) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(settingsIntent)
                }
            }

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(installIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Install intent failed: ${e.message}")
        }
    }
}
