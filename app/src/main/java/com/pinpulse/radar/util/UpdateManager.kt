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
import kotlinx.coroutines.launch
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
    val isMandatory: Boolean = false,
    val minRequiredVersion: String = "",
    val latestVersion: String = "",
    val currentVersion: String = BuildConfig.VERSION_NAME,
    val apkUrl: String = "",
    val releaseNotes: String = "",
    val statusMessage: String? = null,
    val errorMessage: String? = null
)

object UpdateManager {
    private const val TAG = "UpdateManager"
    private const val PRIVATE_GITHUB_REPO = "DALI951/pinpulse"
    private const val PUBLIC_MIRROR_REPO = "DALI951/pinpulse-releases"

    private val isDownloadInProgress = java.util.concurrent.atomic.AtomicBoolean(false)

    private val _updateState = MutableStateFlow(UpdateState())
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    private data class ReleaseCandidate(
        val tagName: String,
        val apkUrl: String,
        val fallbackMirrorApkUrl: String = "",
        val body: String
    )

    private fun decodeGhToken(): String {
        return try {
            val hex = BuildConfig.GH_TOKEN_HEX
            if (hex.isBlank() || hex.length % 2 != 0) return ""
            val bytes = ByteArray(hex.length / 2)
            for (i in bytes.indices) {
                val byteVal = hex.substring(i * 2, i * 2 + 2).toInt(16)
                bytes[i] = (byteVal xor 0x5A).toByte()
            }
            String(bytes, Charsets.UTF_8).trim()
        } catch (_: Exception) {
            ""
        }
    }

    private fun extractApkUrl(releaseObj: JSONObject, preferApiAssetUrl: Boolean): String {
        val assets = releaseObj.optJSONArray("assets") ?: return ""
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name", "")
            if (!name.endsWith(".apk", ignoreCase = true)) continue
            val apiAssetUrl = asset.optString("url", "")
            val browserUrl = asset.optString("browser_download_url", "")
            val chosen = if (preferApiAssetUrl && apiAssetUrl.isNotBlank()) apiAssetUrl else browserUrl
            if (chosen.isNotBlank()) {
                return chosen
            }
        }
        return ""
    }

    /**
     * Scans ALL recent GitHub releases across both the private repository (authenticated via API asset stream)
     * and the public release binary mirror (DALI951/pinpulse-releases), never stopping at the first update found,
     * and always picks the single highest version number that has a ready APK.
     */
    private fun fetchHighestRelease(): ReleaseCandidate? {
        var highestCandidate: ReleaseCandidate? = null
        val token = decodeGhToken()

        val reposToScan = listOf(
            Pair(PUBLIC_MIRROR_REPO, false),
            Pair(PRIVATE_GITHUB_REPO, token.isNotBlank())
        )

        for ((repo, isPrivateRepo) in reposToScan) {
            val listUrl = "https://api.github.com/repos/$repo/releases?per_page=30"
            val latestUrl = "https://api.github.com/repos/$repo/releases/latest"

            // 1. Scan full releases list and compare every release to find the highest version
            try {
                val conn = (URL(listUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    useCaches = false
                    setRequestProperty("User-Agent", "PinPulse-Android")
                    setRequestProperty("Accept", "application/vnd.github.v3+json")
                    setRequestProperty("Cache-Control", "no-cache")
                    if (token.isNotBlank()) {
                        setRequestProperty("Authorization", "Bearer $token")
                    }
                }
                if (conn.responseCode == 200) {
                    val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                    val releasesArray = org.json.JSONArray(jsonStr)
                    for (i in 0 until releasesArray.length()) {
                        val rel = releasesArray.optJSONObject(i) ?: continue
                        if (rel.optBoolean("draft", false)) continue
                        val tag = rel.optString("tag_name", "").trim()
                        if (tag.isBlank()) continue
                        val apk = extractApkUrl(rel, preferApiAssetUrl = isPrivateRepo)
                        if (apk.isBlank()) continue
                        val notes = rel.optString("body", "")

                        if (highestCandidate == null || compareVersions(tag, highestCandidate.tagName)) {
                            highestCandidate = ReleaseCandidate(
                                tagName = tag,
                                apkUrl = apk,
                                body = notes
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Releases list scan fallback ($repo): ${e.message}")
            }

            // 2. Also check /releases/latest and take whichever is strictly newer
            try {
                val conn = (URL(latestUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    useCaches = false
                    setRequestProperty("User-Agent", "PinPulse-Android")
                    setRequestProperty("Accept", "application/vnd.github.v3+json")
                    setRequestProperty("Cache-Control", "no-cache")
                    if (token.isNotBlank()) {
                        setRequestProperty("Authorization", "Bearer $token")
                    }
                }
                if (conn.responseCode == 200) {
                    val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                    val root = JSONObject(jsonStr)
                    val tag = root.optString("tag_name", "").trim()
                    val apk = extractApkUrl(root, preferApiAssetUrl = isPrivateRepo)
                    val notes = root.optString("body", "")
                    if (tag.isNotBlank() && apk.isNotBlank()) {
                        if (highestCandidate == null || compareVersions(tag, highestCandidate.tagName)) {
                            highestCandidate = ReleaseCandidate(
                                tagName = tag,
                                apkUrl = apk,
                                body = notes
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Latest release check failed ($repo): ${e.message}")
            }
        }

        return highestCandidate
    }

    suspend fun checkForUpdates(context: Context? = null): UpdateState = withContext(Dispatchers.IO) {
        _updateState.value = _updateState.value.copy(
            isChecking = true,
            statusMessage = "Checking for latest update...",
            errorMessage = null
        )
        try {
            val highest = fetchHighestRelease()

            if (highest != null) {
                val tagName = highest.tagName
                val apkUrl = highest.apkUrl
                val body = highest.body

                val currentVer = BuildConfig.VERSION_NAME
                val isNewer = compareVersions(tagName, currentVer)
                val cleanLatest = tagName.removePrefix("v").trim()

                // If there is an older cached APK on disk that does NOT match the absolute latest version,
                // purge that older APK immediately so we never install an intermediate update!
                if (context != null && isNewer) {
                    purgeOutdatedCachedApkIfSuperseded(context, cleanLatest)
                }

                // Check if the exact latest update is ALREADY downloaded on this device
                val isAlreadyDownloaded = context?.let { ctx ->
                    isDownloadedApkReady(ctx, cleanLatest)
                } ?: false

                val prefs = context?.let { AppPreferences(it) }
                val minVer = prefs?.getMinRequiredVersion() ?: ""
                val isMandatoryByAdmin = prefs?.isMandatoryUpdateEnforced == true ||
                    (minVer.isNotBlank() && compareVersions(minVer, currentVer))
                val isMandatoryByRelease = body.contains("[MANDATORY]", ignoreCase = true) ||
                    body.contains("mandatory: true", ignoreCase = true) ||
                    body.contains("force_update", ignoreCase = true) ||
                    body.contains("min_version:", ignoreCase = true)
                val isMandatory = isNewer && (isMandatoryByAdmin || isMandatoryByRelease)

                val newState = _updateState.value.copy(
                    isChecking = false,
                    updateAvailable = isNewer && apkUrl.isNotBlank(),
                    isDownloaded = isAlreadyDownloaded,
                    isMandatory = isMandatory,
                    minRequiredVersion = if (isMandatory) (minVer.ifBlank { cleanLatest }) else "",
                    latestVersion = cleanLatest,
                    currentVersion = currentVer,
                    apkUrl = apkUrl,
                    releaseNotes = body,
                    statusMessage = when {
                        isAlreadyDownloaded -> "Latest update v$cleanLatest is downloaded! Ready to install."
                        isNewer && apkUrl.isNotBlank() -> "Latest update available: v$cleanLatest"
                        else -> "You have the latest version ($currentVer)"
                    }
                )
                _updateState.value = newState

                // Silent pre-download: Quietly cache APK in background so user has 0 waiting time!
                if (context != null && isNewer && apkUrl.isNotBlank() && !isAlreadyDownloaded && !_updateState.value.isDownloading) {
                    kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                        downloadSilently(context)
                    }
                }

                newState
            } else {
                val newState = _updateState.value.copy(
                    isChecking = false,
                    errorMessage = "Could not reach GitHub releases",
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

    /**
     * If a cached APK on disk is from an intermediate update (e.g. v2.2 when v2.6 is out),
     * delete it immediately so the user always downloads and installs the newest version directly.
     */
    private fun purgeOutdatedCachedApkIfSuperseded(context: Context, latestVersion: String) {
        try {
            val cleanTarget = latestVersion.removePrefix("v").trim()
            if (cleanTarget.isBlank()) return
            val file = File(context.cacheDir, "pinpulse_update.apk")
            if (!file.exists()) return

            var cachedVersion = ""
            try {
                val pkg = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
                if (pkg != null && pkg.packageName == context.packageName) {
                    cachedVersion = (pkg.versionName ?: "").removePrefix("v").trim()
                }
            } catch (_: Exception) {}

            if (cachedVersion.isBlank()) {
                cachedVersion = AppPreferences(context).getLastDownloadedUpdateVersion().removePrefix("v").trim()
            }

            if (cachedVersion.isNotBlank() && cachedVersion != cleanTarget) {
                Log.d(TAG, "Purging superseded cached APK v$cachedVersion in favor of latest v$cleanTarget")
                file.delete()
                AppPreferences(context).clearLastDownloadedUpdateVersion()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check/purge superseded APK: ${e.message}")
        }
    }

    fun refreshDownloadedStatus(context: Context) {
        val targetVer = _updateState.value.latestVersion
        val isReady = isDownloadedApkReady(context, targetVer)
        _updateState.value = _updateState.value.copy(
            isDownloaded = isReady,
            statusMessage = if (isReady) "Latest update v${targetVer.ifBlank { "new" }} is downloaded! Ready to install." else _updateState.value.statusMessage
        )
    }

    fun setMandatoryEnforced(minVersion: String, mandatory: Boolean = true) {
        _updateState.value = _updateState.value.copy(
            isMandatory = mandatory,
            minRequiredVersion = minVersion
        )
    }

    suspend fun downloadSilently(context: Context) = withContext(Dispatchers.IO) {
        if (_updateState.value.isDownloading || !isDownloadInProgress.compareAndSet(false, true)) {
            Log.d(TAG, "Download already running, skipping duplicate silent request")
            return@withContext
        }
        try {
            val state = _updateState.value
            val targetVer = state.latestVersion
            if (targetVer.isBlank()) return@withContext

            if (isDownloadedApkReady(context, targetVer)) {
                _updateState.value = state.copy(isDownloaded = true, downloadProgress = 1f)
                return@withContext
            }

            val apkUrl = state.apkUrl
            if (apkUrl.isBlank()) return@withContext

            _updateState.value = state.copy(
                isDownloading = true,
                isDownloaded = false,
                downloadProgress = 0f
            )

            var currentUrl = apkUrl
            val token = decodeGhToken()
            var conn: HttpURLConnection
            var redirects = 0
            while (true) {
                val isGitHubApiAsset = currentUrl.contains("api.github.com/")
                conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 15000
                    instanceFollowRedirects = !isGitHubApiAsset
                    setRequestProperty("User-Agent", "PinPulse-Android")
                    if (isGitHubApiAsset) {
                        setRequestProperty("Accept", "application/octet-stream")
                        if (token.isNotBlank()) {
                            setRequestProperty("Authorization", "Bearer $token")
                        }
                    }
                }
                val code = conn.responseCode
                if (code in 300..399) {
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

            AppPreferences(context).setLastDownloadedUpdateVersion(targetVer)
            _updateState.value = _updateState.value.copy(
                isDownloading = false,
                isDownloaded = true,
                downloadProgress = 1f,
                statusMessage = "Update v$targetVer ready to install"
            )
            Log.d(TAG, "Silent pre-download completed successfully for v$targetVer")
        } catch (e: Exception) {
            Log.w(TAG, "Silent pre-download error: ${e.message}")
            _updateState.value = _updateState.value.copy(isDownloading = false)
        } finally {
            isDownloadInProgress.set(false)
        }
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
     * When targetVersion is specified, strictly requires the cached APK to match that exact latest version
     * so an older intermediate APK is never reused when a newer release exists!
     */
    fun isDownloadedApkReady(context: Context, targetVersion: String = ""): Boolean {
        val file = File(context.cacheDir, "pinpulse_update.apk")
        if (!file.exists() || file.length() < 1_500_000) return false
        val cleanTarget = targetVersion.removePrefix("v").trim()

        try {
            val pkg = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
            if (pkg != null && pkg.packageName == context.packageName) {
                val vName = (pkg.versionName ?: "").removePrefix("v").trim()
                return if (cleanTarget.isNotBlank()) {
                    // Strictly match the latest version on GitHub
                    vName == cleanTarget && compareVersions(vName, BuildConfig.VERSION_NAME)
                } else {
                    compareVersions(vName, BuildConfig.VERSION_NAME)
                }
            }
        } catch (e: Exception) {}

        val savedVer = AppPreferences(context).getLastDownloadedUpdateVersion().removePrefix("v").trim()
        if (savedVer.isNotBlank() && file.length() > 5_000_000) {
            return if (cleanTarget.isNotBlank()) {
                savedVer == cleanTarget && compareVersions(savedVer, BuildConfig.VERSION_NAME)
            } else {
                compareVersions(savedVer, BuildConfig.VERSION_NAME)
            }
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
        if (_updateState.value.isDownloading || !isDownloadInProgress.compareAndSet(false, true)) {
            Log.d(TAG, "Update download is already actively running. Rejecting duplicate click.")
            return@withContext
        }
        try {
            // Re-verify the absolute highest release on GitHub right before installing/downloading
            // so we never install an older version if a newer release is available.
            try {
                val freshHighest = fetchHighestRelease()
                if (freshHighest != null) {
                    val freshClean = freshHighest.tagName.removePrefix("v").trim()
                    if (freshClean.isNotBlank() && compareVersions(freshClean, BuildConfig.VERSION_NAME)) {
                        purgeOutdatedCachedApkIfSuperseded(context, freshClean)
                        _updateState.value = _updateState.value.copy(
                            latestVersion = freshClean,
                            apkUrl = freshHighest.apkUrl,
                            releaseNotes = freshHighest.body
                        )
                    }
                }
            } catch (_: Exception) {}

            val latestVer = _updateState.value.latestVersion

            // 1. If the exact latest APK is ALREADY downloaded and valid, launch installer immediately!
            val existingFile = getDownloadedUpdateApk(context, latestVer)
            if (existingFile != null) {
                Log.d(TAG, "Update APK already downloaded: ${existingFile.absolutePath} (${existingFile.length() / 1024} KB) -> Reusing file without downloading!")
                _updateState.value = _updateState.value.copy(
                    isDownloading = false,
                    isDownloaded = true,
                    downloadProgress = 1f,
                    statusMessage = "Launching installer for v$latestVer..."
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

            var currentUrl = apkUrl
            val token = decodeGhToken()
            var conn: HttpURLConnection
            var redirects = 0
            while (true) {
                val isGitHubApiAsset = currentUrl.contains("api.github.com/")
                conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 15000
                    instanceFollowRedirects = !isGitHubApiAsset
                    setRequestProperty("User-Agent", "PinPulse-Android")
                    if (isGitHubApiAsset) {
                        setRequestProperty("Accept", "application/octet-stream")
                        if (token.isNotBlank()) {
                            setRequestProperty("Authorization", "Bearer $token")
                        }
                    }
                }
                val code = conn.responseCode
                if (code == HttpURLConnection.HTTP_MOVED_PERM || code == HttpURLConnection.HTTP_MOVED_TEMP || code == 303 || code == 307 || code == 308) {
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
        } finally {
            isDownloadInProgress.set(false)
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
