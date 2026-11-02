package com.pinpulse.radar.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.pinpulse.radar.BuildConfig
import com.pinpulse.radar.data.AppPreferences
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
    val isDownloaded: Boolean = false, // True if a valid update APK is ready on device
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

    suspend fun checkForUpdates(context: Context? = null): UpdateState = withContext(Dispatchers.IO) {
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
                val cleanLatest = tagName.removePrefix("v")

                // Check if the update is ALREADY downloaded on this device
                val isAlreadyDownloaded = context?.let { ctx ->
                    isDownloadedApkReady(ctx, cleanLatest)
                } ?: false

                val newState = _updateState.value.copy(
                    isChecking = false,
                    updateAvailable = isNewer && apkUrl.isNotBlank(),
                    isDownloaded = isAlreadyDownloaded,
                    latestVersion = cleanLatest,
                    currentVersion = currentVer,
                    apkUrl = apkUrl,
                    releaseNotes = body,
                    statusMessage = when {
                        isAlreadyDownloaded -> "Update v$cleanLatest is already downloaded! Ready to install."
                        isNewer && apkUrl.isNotBlank() -> "Update available: $tagName"
                        else -> "You have the latest version ($currentVer)"
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

    fun refreshDownloadedStatus(context: Context) {
        val targetVer = _updateState.value.latestVersion
        val isReady = isDownloadedApkReady(context, targetVer)
        _updateState.value = _updateState.value.copy(
            isDownloaded = isReady,
            statusMessage = if (isReady) "Update v$targetVer is already downloaded! Ready to install." else _updateState.value.statusMessage
        )
    }

    fun compareVersions(latestTag: String, currentVer: String): Boolean {
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

    /**
     * Check if a valid update APK is already downloaded on disk and matches target version.
     */
    fun isDownloadedApkReady(context: Context, targetVersion: String = ""): Boolean {
        val file = File(context.cacheDir, "pinpulse_update.apk")
        if (!file.exists() || file.length() < 1_500_000) return false

        try {
            val pkg = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
            if (pkg != null && pkg.packageName == context.packageName) {
                val vName = pkg.versionName ?: ""
                if (targetVersion.isNotBlank()) {
                    if (vName == targetVersion.removePrefix("v")) return true
                }
                if (compareVersions(vName, BuildConfig.VERSION_NAME)) return true
            }
        } catch (e: Exception) {}

        val savedVer = AppPreferences(context).getLastDownloadedUpdateVersion()
        if (savedVer.isNotBlank() && file.length() > 5_000_000) {
            if (targetVersion.isNotBlank() && savedVer == targetVersion.removePrefix("v")) return true
            if (compareVersions(savedVer, BuildConfig.VERSION_NAME)) return true
        }

        return false
    }

    /**
     * Get the downloaded update APK if it is valid and ready to install.
     */
    fun getDownloadedUpdateApk(context: Context, targetVersion: String = ""): File? {
        val file = File(context.cacheDir, "pinpulse_update.apk")
        return if (isDownloadedApkReady(context, targetVersion)) file else null
    }

    suspend fun downloadAndInstall(context: Context, onInstalled: () -> Unit = {}) = withContext(Dispatchers.IO) {
        val latestVer = _updateState.value.latestVersion

        // 1. If APK is ALREADY downloaded and valid, do NOT re-download! Launch installer immediately!
        val existingFile = getDownloadedUpdateApk(context, latestVer)
        if (existingFile != null) {
            Log.d(TAG, "Update APK already downloaded: ${existingFile.absolutePath} (${existingFile.length() / 1024} KB) -> Reusing file without downloading!")
            _updateState.value = _updateState.value.copy(
                isDownloading = false,
                isDownloaded = true,
                downloadProgress = 1f,
                statusMessage = "Launching installer..."
            )
            withContext(Dispatchers.Main) {
                installApk(context, existingFile)
                onInstalled()
            }
            return@withContext
        }

        val apkUrl = _updateState.value.apkUrl
        if (apkUrl.isBlank()) return@withContext

        // Pre-clean older/incomplete files before starting fresh download
        cleanupDownloadedApks(context)

        _updateState.value = _updateState.value.copy(
            isDownloading = true,
            isDownloaded = false,
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

            // Save version so if user cancels or leaves installer, we remember it was downloaded
            AppPreferences(context).setLastDownloadedUpdateVersion(latestVer)

            _updateState.value = _updateState.value.copy(
                isDownloading = false,
                isDownloaded = true,
                downloadProgress = 1f,
                statusMessage = "Download complete! Ready to install."
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

    /**
     * Automatically cleans up APK files from PREVIOUS updates ONLY IF
     * the currently running app version is already equal to or newer than the APK version.
     * If the running app is still older (i.e. the user downloaded but hasn't installed yet),
     * the APK is PRESERVED so the user is never required to re-download!
     */
    fun cleanupOldInstalledApks(context: Context): Long {
        var totalFreed = 0L
        try {
            val file = File(context.cacheDir, "pinpulse_update.apk")
            if (file.exists()) {
                val isStillNewer = isDownloadedApkReady(context)
                if (!isStillNewer) {
                    val len = file.length()
                    if (file.delete()) {
                        totalFreed += len
                        AppPreferences(context).clearLastDownloadedUpdateVersion()
                        Log.d(TAG, "Pruned installed APK ($len bytes) - update successfully applied to v${BuildConfig.VERSION_NAME}")
                    }
                } else {
                    Log.d(TAG, "Preserving downloaded update APK (${file.length() / 1024} KB) - pending install by user.")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error cleaning old installed APKs: ${e.message}")
        }
        return totalFreed
    }

    /**
     * Unconditional cache purge (e.g. user manually taps 'Purge Storage' in Settings).
     */
    fun cleanupDownloadedApks(context: Context): Long {
        var totalFreed = 0L
        try {
            context.cacheDir?.listFiles()?.forEach { file ->
                if (file.isFile && file.name.endsWith(".apk", ignoreCase = true)) {
                    val len = file.length()
                    if (file.delete()) {
                        totalFreed += len
                    }
                }
            }
            context.externalCacheDir?.listFiles()?.forEach { file ->
                if (file.isFile && file.name.endsWith(".apk", ignoreCase = true)) {
                    val len = file.length()
                    if (file.delete()) {
                        totalFreed += len
                    }
                }
            }
            val updatesDir = File(context.cacheDir, "updates")
            if (updatesDir.exists()) {
                updatesDir.listFiles()?.forEach { f ->
                    if (f.isFile && f.name.endsWith(".apk", ignoreCase = true)) {
                        totalFreed += f.length()
                        f.delete()
                    }
                }
                updatesDir.delete()
            }
            AppPreferences(context).clearLastDownloadedUpdateVersion()
            _updateState.value = _updateState.value.copy(isDownloaded = false)
        } catch (e: Exception) {
            Log.w(TAG, "Error cleaning cached update APKs: ${e.message}")
        }
        return totalFreed
    }

    /**
     * Calculate current storage used by downloaded update APKs.
     */
    fun getCachedApkSizeBytes(context: Context): Long {
        var total = 0L
        try {
            context.cacheDir?.listFiles()?.forEach { file ->
                if (file.isFile && file.name.endsWith(".apk", ignoreCase = true)) {
                    total += file.length()
                }
            }
            context.externalCacheDir?.listFiles()?.forEach { file ->
                if (file.isFile && file.name.endsWith(".apk", ignoreCase = true)) {
                    total += file.length()
                }
            }
        } catch (e: Exception) {}
        return total
    }
}
