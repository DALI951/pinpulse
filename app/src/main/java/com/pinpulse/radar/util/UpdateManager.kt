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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

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
    private const val CHECK_DEBOUNCE_MS = 45_000L
    private const val APK_CACHE_NAME = "pinpulse_update.apk"
    private const val APK_TEMP_NAME = "pinpulse_update.apk.tmp"

    private val backgroundScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val checkMutex = Mutex()
    private val isDownloadInProgress = AtomicBoolean(false)

    @Volatile
    private var lastCheckTimestampMs: Long = 0L

    @Volatile
    private var lastCheckedEmail: String = ""

    @Volatile
    private var fallbackMirrorUrlCache: String = ""

    @Volatile
    private var pendingInstallAfterDownload: Boolean = false

    @Volatile
    private var pendingInstallCallback: (() -> Unit)? = null

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

    private fun buildMirrorDownloadUrl(tag: String): String {
        val cleanVer = tag.removePrefix("v").removePrefix("V").trim()
        if (cleanVer.isBlank()) return ""
        return "https://github.com/$PUBLIC_MIRROR_REPO/releases/download/v$cleanVer/PinPulse-v$cleanVer.apk"
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

    private data class ApiResponse(
        val code: Int,
        val body: String?,
        val usedAuth: Boolean
    )

    /**
     * Opens a GitHub API JSON endpoint.
     * - NEVER attaches Authorization header for PUBLIC_MIRROR_REPO (allowAuth = false).
     * - If an authenticated call returns 401 or 403, immediately retries unauthenticated.
     */
    private fun fetchGitHubApiJson(urlStr: String, allowAuth: Boolean, token: String): ApiResponse {
        fun executeOnce(withBearer: Boolean): Pair<Int, String?> {
            var conn: HttpURLConnection? = null
            return try {
                conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    useCaches = false
                    setRequestProperty("User-Agent", "PinPulse-Android")
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("Cache-Control", "no-cache")
                    if (withBearer && token.isNotBlank()) {
                        setRequestProperty("Authorization", "Bearer $token")
                    }
                }
                val code = conn.responseCode
                val text = if (code == 200) {
                    conn.inputStream.bufferedReader().use { it.readText() }
                } else {
                    null
                }
                Pair(code, text)
            } finally {
                try {
                    conn?.disconnect()
                } catch (_: Exception) {}
            }
        }

        val shouldSendAuth = allowAuth && token.isNotBlank()
        val (firstCode, firstBody) = executeOnce(withBearer = shouldSendAuth)
        if (shouldSendAuth && (firstCode == 401 || firstCode == 403)) {
            Log.w(TAG, "Authenticated GitHub API returned $firstCode on $urlStr; retrying unauthenticated")
            val (retryCode, retryBody) = executeOnce(withBearer = false)
            return ApiResponse(code = retryCode, body = retryBody, usedAuth = false)
        }
        return ApiResponse(code = firstCode, body = firstBody, usedAuth = shouldSendAuth && firstCode == 200)
    }

    /**
     * Zero-API-quota fallback: resolves https://github.com/DALI951/pinpulse-releases/releases/latest
     * via HTTP 302 redirect (or HTML tag link) to /releases/tag/vX.Y and constructs PinPulse-vX.Y.apk URL.
     */
    private fun fetchFromPublicHtmlRedirect(): ReleaseCandidate? {
        val latestPageUrl = "https://github.com/$PUBLIC_MIRROR_REPO/releases/latest"
        val tagRegex = Regex("""/releases/tag/(v?[0-9]+(?:\.[0-9]+)+)""", RegexOption.IGNORE_CASE)
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(latestPageUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                useCaches = false
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "PinPulse-Android")
                setRequestProperty("Accept", "text/html,application/xhtml+xml")
            }
            val code = conn.responseCode
            var matchedTag: String? = null

            if (code in 300..399) {
                val location = conn.getHeaderField("Location").orEmpty()
                matchedTag = tagRegex.find(location)?.groupValues?.getOrNull(1)
            } else if (code == 200) {
                matchedTag = tagRegex.find(conn.url?.toString().orEmpty())?.groupValues?.getOrNull(1)
                if (matchedTag.isNullOrBlank()) {
                    val htmlSnippet = conn.inputStream.bufferedReader().use { reader ->
                        val buf = CharArray(32768)
                        val n = reader.read(buf)
                        if (n > 0) String(buf, 0, n) else ""
                    }
                    matchedTag = tagRegex.find(htmlSnippet)?.groupValues?.getOrNull(1)
                }
            }

            val cleanVer = matchedTag.orEmpty().removePrefix("v").removePrefix("V").trim()
            if (cleanVer.isNotBlank()) {
                val normalizedTag = "v$cleanVer"
                val directApkUrl = buildMirrorDownloadUrl(normalizedTag)
                Log.i(TAG, "Resolved latest release $normalizedTag via GitHub HTML redirect fallback")
                ReleaseCandidate(
                    tagName = normalizedTag,
                    apkUrl = directApkUrl,
                    fallbackMirrorApkUrl = directApkUrl,
                    body = ""
                )
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "HTML redirect fallback failed: ${e.message}")
            null
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {}
        }
    }

    /**
     * Scans recent GitHub releases across the public release mirror (always unauthenticated)
     * and the main repository, skipping /releases/latest whenever /releases?per_page=30 succeeds,
     * and falling back to GitHub's HTML 302 redirect if API rate-limits (403/429) or fails.
     */
    private fun fetchHighestRelease(targetEmail: String = ""): ReleaseCandidate? {
        var highestCandidate: ReleaseCandidate? = null
        var sawRateLimit = false
        var mirrorSucceeded = false
        val token = decodeGhToken()

        val reposToScan = listOf(
            Pair(PUBLIC_MIRROR_REPO, false),
            Pair(PRIVATE_GITHUB_REPO, true)
        )

        for ((repo, allowAuthForRepo) in reposToScan) {
            // Avoid wasting unauthenticated API quota on the second repo when the public mirror succeeded
            if (repo == PRIVATE_GITHUB_REPO && mirrorSucceeded && token.isBlank()) {
                continue
            }

            val listUrl = "https://api.github.com/repos/$repo/releases?per_page=30"
            val latestUrl = "https://api.github.com/repos/$repo/releases/latest"
            var listSucceeded = false

            // 1. Scan releases list (?per_page=30)
            try {
                val resp = fetchGitHubApiJson(listUrl, allowAuth = allowAuthForRepo, token = token)
                if (resp.code == 403 || resp.code == 429) {
                    sawRateLimit = true
                }
                if (resp.code == 200 && !resp.body.isNullOrBlank()) {
                    val releasesArray = JSONArray(resp.body)
                    if (releasesArray.length() > 0) {
                        listSucceeded = true
                        if (repo == PUBLIC_MIRROR_REPO) {
                            mirrorSucceeded = true
                        }
                    }
                    val preferApiStream = (repo == PRIVATE_GITHUB_REPO) && resp.usedAuth
                    for (i in 0 until releasesArray.length()) {
                        val rel = releasesArray.optJSONObject(i) ?: continue
                        if (rel.optBoolean("draft", false)) continue
                        val tag = rel.optString("tag_name", "").trim()
                        if (tag.isBlank()) continue
                        val apk = extractApkUrl(rel, preferApiAssetUrl = preferApiStream)
                        if (apk.isBlank()) continue
                        val notes = rel.optString("body", "")

                        if (!isUserTargetedForRelease(notes, targetEmail)) {
                            continue
                        }

                        if (highestCandidate == null || compareVersions(tag, highestCandidate.tagName)) {
                            highestCandidate = ReleaseCandidate(
                                tagName = tag,
                                apkUrl = apk,
                                fallbackMirrorApkUrl = buildMirrorDownloadUrl(tag),
                                body = notes
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Releases list scan fallback ($repo): ${e.message}")
            }

            // 2. Only hit /releases/latest if /releases?per_page=30 did NOT succeed
            if (!listSucceeded) {
                try {
                    val resp = fetchGitHubApiJson(latestUrl, allowAuth = allowAuthForRepo, token = token)
                    if (resp.code == 403 || resp.code == 429) {
                        sawRateLimit = true
                    }
                    if (resp.code == 200 && !resp.body.isNullOrBlank()) {
                        val root = JSONObject(resp.body)
                        val tag = root.optString("tag_name", "").trim()
                        val preferApiStream = (repo == PRIVATE_GITHUB_REPO) && resp.usedAuth
                        val apk = extractApkUrl(root, preferApiAssetUrl = preferApiStream)
                        val notes = root.optString("body", "")
                        if (tag.isNotBlank() && apk.isNotBlank() && isUserTargetedForRelease(notes, targetEmail)) {
                            if (repo == PUBLIC_MIRROR_REPO) {
                                mirrorSucceeded = true
                            }
                            if (highestCandidate == null || compareVersions(tag, highestCandidate.tagName)) {
                                highestCandidate = ReleaseCandidate(
                                    tagName = tag,
                                    apkUrl = apk,
                                    fallbackMirrorApkUrl = buildMirrorDownloadUrl(tag),
                                    body = notes
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Latest release check failed ($repo): ${e.message}")
                }
            }
        }

        // 3. Fallback to non-API GitHub HTML /releases/latest redirect if API was rate-limited or unreachable
        if (highestCandidate == null || sawRateLimit) {
            val htmlFallback = fetchFromPublicHtmlRedirect()
            if (htmlFallback != null && (highestCandidate == null || compareVersions(htmlFallback.tagName, highestCandidate.tagName))) {
                highestCandidate = htmlFallback
            }
        }

        return highestCandidate
    }

    suspend fun checkForUpdates(context: Context? = null, force: Boolean = false): UpdateState = withContext(Dispatchers.IO) {
        checkMutex.withLock {
            val prefs = context?.let { AppPreferences(it) }
            val currentEmail = prefs?.loadCurrentUser()?.email.orEmpty()
            val now = System.currentTimeMillis()

            // 45s debounce unless force = true or previous check had an error
            val currentSnapshot = _updateState.value
            if (!force &&
                lastCheckTimestampMs > 0L &&
                (now - lastCheckTimestampMs) < CHECK_DEBOUNCE_MS &&
                currentEmail == lastCheckedEmail &&
                currentSnapshot.errorMessage == null &&
                currentSnapshot.latestVersion.isNotBlank()
            ) {
                val currentVer = BuildConfig.VERSION_NAME
                val cleanLatest = currentSnapshot.latestVersion
                val isAlreadyDownloaded = context?.let { isDownloadedApkReady(it, cleanLatest) } ?: currentSnapshot.isDownloaded
                val minVer = prefs?.getMinRequiredVersion().orEmpty()
                val isMandatoryByAdmin = prefs?.isMandatoryUpdateEnforced == true ||
                    (minVer.isNotBlank() && compareVersions(minVer, currentVer))
                val isMandatory = currentSnapshot.updateAvailable && (currentSnapshot.isMandatory || isMandatoryByAdmin)

                val refreshed = currentSnapshot.copy(
                    isChecking = false,
                    isDownloaded = isAlreadyDownloaded,
                    isMandatory = isMandatory,
                    minRequiredVersion = if (isMandatory) minVer.ifBlank { cleanLatest } else ""
                )
                _updateState.value = refreshed

                if (context != null && refreshed.updateAvailable && refreshed.apkUrl.isNotBlank() && !isAlreadyDownloaded && !refreshed.isDownloading) {
                    backgroundScope.launch {
                        downloadSilently(context)
                    }
                }
                return@withLock refreshed
            }

            _updateState.value = _updateState.value.copy(
                isChecking = true,
                statusMessage = "Checking for latest update...",
                errorMessage = null
            )

            try {
                val highest = fetchHighestRelease(currentEmail)

                if (highest != null) {
                    lastCheckTimestampMs = System.currentTimeMillis()
                    lastCheckedEmail = currentEmail
                    fallbackMirrorUrlCache = highest.fallbackMirrorApkUrl

                    val tagName = highest.tagName
                    val apkUrl = highest.apkUrl
                    val body = highest.body

                    val currentVer = BuildConfig.VERSION_NAME
                    val isNewer = compareVersions(tagName, currentVer)
                    val cleanLatest = tagName.removePrefix("v").removePrefix("V").trim()
                    val isTargeted = isUserTargetedForRelease(body, currentEmail)
                    val effectiveIsNewer = isNewer && isTargeted

                    if (context != null && effectiveIsNewer) {
                        purgeOutdatedCachedApkIfSuperseded(context, cleanLatest)
                    }

                    val isAlreadyDownloaded = context?.let { ctx ->
                        isDownloadedApkReady(ctx, cleanLatest)
                    } ?: false

                    val minVer = prefs?.getMinRequiredVersion() ?: ""
                    val isMandatoryByAdmin = prefs?.isMandatoryUpdateEnforced == true ||
                        (minVer.isNotBlank() && compareVersions(minVer, currentVer))
                    val isMandatoryByRelease = body.contains("[MANDATORY]", ignoreCase = true)
                    val isMandatory = effectiveIsNewer && (isMandatoryByAdmin || isMandatoryByRelease)

                    val newState = _updateState.value.copy(
                        isChecking = false,
                        updateAvailable = effectiveIsNewer && apkUrl.isNotBlank(),
                        isDownloaded = isAlreadyDownloaded,
                        isMandatory = isMandatory,
                        minRequiredVersion = if (isMandatory) (minVer.ifBlank { cleanLatest }) else "",
                        latestVersion = cleanLatest,
                        currentVersion = currentVer,
                        apkUrl = apkUrl,
                        releaseNotes = body,
                        errorMessage = null,
                        statusMessage = when {
                            !isTargeted -> "You have the latest version ($currentVer)"
                            isAlreadyDownloaded -> "Latest update v$cleanLatest is downloaded! Ready to install."
                            effectiveIsNewer && apkUrl.isNotBlank() -> "Latest update available: v$cleanLatest"
                            else -> "You have the latest version ($currentVer)"
                        }
                    )
                    _updateState.value = newState

                    // Silent pre-download ONLY when effectiveIsNewer is true
                    if (context != null && effectiveIsNewer && apkUrl.isNotBlank() && !isAlreadyDownloaded && !_updateState.value.isDownloading) {
                        backgroundScope.launch {
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
    }

    /**
     * Validates that an APK file on disk is a complete, parseable Android package matching this app.
     */
    private fun verifyApkArchive(context: Context, file: File, expectedVersion: String = ""): Boolean {
        if (!file.exists() || file.length() < 1_500_000L) return false
        return try {
            val pkg = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0) ?: return false
            if (pkg.packageName != context.packageName) return false
            val archiveVer = (pkg.versionName ?: "").removePrefix("v").removePrefix("V").trim()
            val cleanExpected = expectedVersion.removePrefix("v").removePrefix("V").trim()
            if (cleanExpected.isNotBlank() && archiveVer.isNotBlank()) {
                archiveVer == cleanExpected && compareVersions(archiveVer, BuildConfig.VERSION_NAME)
            } else if (archiveVer.isNotBlank()) {
                compareVersions(archiveVer, BuildConfig.VERSION_NAME)
            } else {
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "APK archive verification failed for ${file.name}: ${e.message}")
            false
        }
    }

    /**
     * Downloads APK stream to a temporary .tmp file, verifies Content-Length and Android package archive
     * integrity, and atomically renames it to pinpulse_update.apk.
     * Falls back to public mirror direct download URL if primary URL returns 401/403 or fails.
     */
    private fun downloadVerifiedApkToCache(
        context: Context,
        targetVersion: String,
        primaryUrl: String,
        fallbackMirrorUrl: String
    ): File {
        val candidateUrls = buildList {
            if (primaryUrl.isNotBlank()) add(primaryUrl)
            val mirror = fallbackMirrorUrl.ifBlank { buildMirrorDownloadUrl(targetVersion) }
            if (mirror.isNotBlank() && mirror != primaryUrl) add(mirror)
        }
        if (candidateUrls.isEmpty()) {
            throw IOException("No valid APK download URL available")
        }

        val token = decodeGhToken()
        var lastError: Exception? = null

        for (startUrl in candidateUrls) {
            val tempFile = File(context.cacheDir, APK_TEMP_NAME)
            var conn: HttpURLConnection? = null
            try {
                if (tempFile.exists()) tempFile.delete()

                var currentUrl = startUrl
                var useAuthOnApi = token.isNotBlank()
                var redirects = 0

                while (true) {
                    val isPrivateApiAsset = currentUrl.contains("api.github.com/") &&
                        !currentUrl.contains(PUBLIC_MIRROR_REPO, ignoreCase = true)

                    conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15000
                        readTimeout = 20000
                        instanceFollowRedirects = !isPrivateApiAsset
                        setRequestProperty("User-Agent", "PinPulse-Android")
                        if (isPrivateApiAsset) {
                            setRequestProperty("Accept", "application/octet-stream")
                            if (useAuthOnApi) {
                                setRequestProperty("Authorization", "Bearer $token")
                            }
                        }
                    }

                    val code = conn.responseCode
                    if (isPrivateApiAsset && useAuthOnApi && (code == 401 || code == 403)) {
                        conn.disconnect()
                        useAuthOnApi = false
                        continue
                    }

                    if (code == HttpURLConnection.HTTP_MOVED_PERM ||
                        code == HttpURLConnection.HTTP_MOVED_TEMP ||
                        code == HttpURLConnection.HTTP_SEE_OTHER ||
                        code == 307 ||
                        code == 308
                    ) {
                        val nextLoc = conn.getHeaderField("Location")
                        conn.disconnect()
                        if (nextLoc.isNullOrBlank()) break
                        currentUrl = nextLoc
                        redirects++
                        if (redirects > 6) throw IOException("Too many redirects while downloading APK")
                    } else {
                        break
                    }
                }

                val finalConn = conn ?: throw IOException("Connection could not be opened")
                val status = finalConn.responseCode
                if (status !in 200..299) {
                    throw IOException("HTTP $status while downloading update APK")
                }

                val contentLength = finalConn.contentLengthLong
                var totalRead = 0L

                finalConn.inputStream.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                            if (contentLength > 0L) {
                                val progress = (totalRead.toFloat() / contentLength).coerceIn(0f, 1f)
                                _updateState.value = _updateState.value.copy(downloadProgress = progress)
                            }
                        }
                        output.fd.sync()
                    }
                }

                if (contentLength > 0L && totalRead != contentLength) {
                    tempFile.delete()
                    throw IOException("Incomplete APK download ($totalRead of $contentLength bytes)")
                }

                if (!verifyApkArchive(context, tempFile, targetVersion)) {
                    tempFile.delete()
                    throw IOException("Downloaded APK failed package signature/archive verification")
                }

                val destinationFile = File(context.cacheDir, APK_CACHE_NAME)
                if (!tempFile.renameTo(destinationFile)) {
                    if (destinationFile.exists()) destinationFile.delete()
                    if (!tempFile.renameTo(destinationFile)) {
                        tempFile.copyTo(destinationFile, overwrite = true)
                        tempFile.delete()
                    }
                }

                AppPreferences(context).setLastDownloadedUpdateVersion(targetVersion.removePrefix("v").trim())
                return destinationFile
            } catch (e: Exception) {
                if (tempFile.exists()) tempFile.delete()
                lastError = e
                Log.w(TAG, "APK download attempt failed ($startUrl): ${e.message}")
            } finally {
                try {
                    conn?.disconnect()
                } catch (_: Exception) {}
            }
        }

        throw lastError ?: IOException("Failed to download update APK")
    }

    /**
     * If a cached APK on disk is from an intermediate update (e.g. v4.2 when v4.5 is out),
     * delete it immediately so the user always downloads and installs the newest version directly.
     */
    private fun purgeOutdatedCachedApkIfSuperseded(context: Context, latestVersion: String) {
        try {
            val cleanTarget = latestVersion.removePrefix("v").trim()
            if (cleanTarget.isBlank()) return
            val file = File(context.cacheDir, APK_CACHE_NAME)
            if (!file.exists()) return

            var cachedVersion = ""
            try {
                val pkg = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
                if (pkg != null && pkg.packageName == context.packageName) {
                    cachedVersion = (pkg.versionName ?: "").removePrefix("v").trim()
                } else {
                    // Corrupt cached file — purge immediately
                    file.delete()
                    AppPreferences(context).clearLastDownloadedUpdateVersion()
                    return
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
                val existingFile = File(context.cacheDir, APK_CACHE_NAME)
                _updateState.value = _updateState.value.copy(isDownloaded = true, downloadProgress = 1f)
                if (pendingInstallAfterDownload) {
                    pendingInstallAfterDownload = false
                    val cb = pendingInstallCallback
                    pendingInstallCallback = null
                    withContext(Dispatchers.Main) {
                        installApk(context, existingFile)
                        cb?.invoke()
                    }
                }
                return@withContext
            }

            val apkUrl = state.apkUrl
            if (apkUrl.isBlank()) return@withContext

            _updateState.value = state.copy(
                isDownloading = true,
                isDownloaded = false,
                downloadProgress = 0f
            )

            val destinationFile = downloadVerifiedApkToCache(
                context = context,
                targetVersion = targetVer,
                primaryUrl = apkUrl,
                fallbackMirrorUrl = fallbackMirrorUrlCache
            )

            _updateState.value = _updateState.value.copy(
                isDownloading = false,
                isDownloaded = true,
                downloadProgress = 1f,
                errorMessage = null,
                statusMessage = "Update v$targetVer ready to install"
            )
            Log.d(TAG, "Silent pre-download completed successfully for v$targetVer")

            // Auto-launch installer if the user tapped Update while silent pre-download was in flight
            if (pendingInstallAfterDownload) {
                pendingInstallAfterDownload = false
                val cb = pendingInstallCallback
                pendingInstallCallback = null
                withContext(Dispatchers.Main) {
                    installApk(context, destinationFile)
                    cb?.invoke()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Silent pre-download error: ${e.message}")
            val shouldSurfaceError = pendingInstallAfterDownload
            pendingInstallAfterDownload = false
            pendingInstallCallback = null
            _updateState.value = _updateState.value.copy(
                isDownloading = false,
                errorMessage = if (shouldSurfaceError) "Download failed: ${e.message}" else _updateState.value.errorMessage
            )
        } finally {
            isDownloadInProgress.set(false)
        }
    }

    fun isUserTargetedForRelease(releaseNotes: String, userEmail: String): Boolean {
        if (releaseNotes.isBlank()) return true
        val cleanEmail = userEmail.trim().lowercase()
        val pattern = Regex("""\[(?:TARGET|TARGET_EMAILS|EMAILS):\s*([^\]]+)\]""", RegexOption.IGNORE_CASE)
        val match = pattern.find(releaseNotes) ?: return true
        val rawEmails = match.groupValues[1]
        val allowedEmails = rawEmails.split(",", ";", " ", "\n", "\t")
            .map { it.trim().lowercase() }
            .filter { it.contains("@") }
        if (allowedEmails.isEmpty()) return true
        return cleanEmail.isNotBlank() && allowedEmails.contains(cleanEmail)
    }

    fun compareVersions(latestTag: String, currentVer: String): Boolean {
        try {
            val cleanLatest = latestTag.removePrefix("v").removePrefix("V").trim()
            val cleanCurrent = currentVer.removePrefix("v").removePrefix("V").trim()
            if (cleanLatest == cleanCurrent) return false

            val latestParts = cleanLatest.split(".").mapNotNull { it.toIntOrNull() }
            val currentParts = cleanCurrent.split(".").mapNotNull { it.toIntOrNull() }
            if (latestParts.isEmpty() || currentParts.isEmpty()) return false

            val maxLen = maxOf(latestParts.size, currentParts.size)
            for (i in 0 until maxLen) {
                val l = latestParts.getOrElse(i) { 0 }
                val c = currentParts.getOrElse(i) { 0 }
                if (l > c) return true
                if (l < c) return false
            }
        } catch (_: Exception) {}
        return false
    }

    /**
     * Check if a valid, verified update APK is already downloaded on disk and matches target version.
     */
    fun isDownloadedApkReady(context: Context, targetVersion: String = ""): Boolean {
        val file = File(context.cacheDir, APK_CACHE_NAME)
        return verifyApkArchive(context, file, targetVersion)
    }

    /**
     * Get the downloaded update APK if it is valid and ready to install.
     */
    fun getDownloadedUpdateApk(context: Context, targetVersion: String = ""): File? {
        val file = File(context.cacheDir, APK_CACHE_NAME)
        return if (isDownloadedApkReady(context, targetVersion)) file else null
    }

    suspend fun downloadAndInstall(context: Context, onInstalled: () -> Unit = {}) = withContext(Dispatchers.IO) {
        if (_updateState.value.isDownloading || !isDownloadInProgress.compareAndSet(false, true)) {
            Log.d(TAG, "Silent download already running; queueing auto-install upon completion.")
            pendingInstallAfterDownload = true
            pendingInstallCallback = onInstalled
            _updateState.value = _updateState.value.copy(
                errorMessage = null,
                statusMessage = "Downloading update... Installer will open automatically."
            )
            return@withContext
        }
        try {
            val now = System.currentTimeMillis()
            if ((now - lastCheckTimestampMs) >= CHECK_DEBOUNCE_MS || _updateState.value.apkUrl.isBlank()) {
                try {
                    val currentEmail = AppPreferences(context).loadCurrentUser()?.email.orEmpty()
                    val freshHighest = fetchHighestRelease(currentEmail)
                    if (freshHighest != null) {
                        lastCheckTimestampMs = System.currentTimeMillis()
                        lastCheckedEmail = currentEmail
                        fallbackMirrorUrlCache = freshHighest.fallbackMirrorApkUrl
                        val freshClean = freshHighest.tagName.removePrefix("v").removePrefix("V").trim()
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
            }

            val latestVer = _updateState.value.latestVersion

            // 1. If the exact latest APK is ALREADY downloaded and verified, launch installer immediately
            val existingFile = getDownloadedUpdateApk(context, latestVer)
            if (existingFile != null) {
                Log.d(TAG, "Verified APK already downloaded (${existingFile.length() / 1024} KB) -> launching installer")
                _updateState.value = _updateState.value.copy(
                    isDownloading = false,
                    isDownloaded = true,
                    downloadProgress = 1f,
                    errorMessage = null,
                    statusMessage = "Launching installer for v$latestVer..."
                )
                withContext(Dispatchers.Main) {
                    installApk(context, existingFile)
                    onInstalled()
                }
                return@withContext
            }

            val apkUrl = _updateState.value.apkUrl
            if (apkUrl.isBlank() && latestVer.isBlank()) return@withContext

            cleanupDownloadedApks(context)

            _updateState.value = _updateState.value.copy(
                isDownloading = true,
                isDownloaded = false,
                downloadProgress = 0f,
                errorMessage = null,
                statusMessage = "Downloading update..."
            )

            val destinationFile = downloadVerifiedApkToCache(
                context = context,
                targetVersion = latestVer,
                primaryUrl = apkUrl,
                fallbackMirrorUrl = fallbackMirrorUrlCache
            )

            _updateState.value = _updateState.value.copy(
                isDownloading = false,
                isDownloaded = true,
                downloadProgress = 1f,
                errorMessage = null,
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
            pendingInstallAfterDownload = false
            pendingInstallCallback = null
            isDownloadInProgress.set(false)
        }
    }

    fun installApk(context: Context, file: File) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(settingsIntent)
                    _updateState.value = _updateState.value.copy(
                        statusMessage = "Allow 'Install unknown apps', then tap Install Now."
                    )
                    return
                }
            }

            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
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
     */
    fun cleanupOldInstalledApks(context: Context): Long {
        var totalFreed = 0L
        try {
            val tmpFile = File(context.cacheDir, APK_TEMP_NAME)
            if (tmpFile.exists() && !isDownloadInProgress.get()) {
                totalFreed += tmpFile.length()
                tmpFile.delete()
            }
            val file = File(context.cacheDir, APK_CACHE_NAME)
            if (file.exists()) {
                val isStillNewer = isDownloadedApkReady(context)
                if (!isStillNewer) {
                    val len = file.length()
                    if (file.delete()) {
                        totalFreed += len
                        AppPreferences(context).clearLastDownloadedUpdateVersion()
                        Log.d(TAG, "Pruned installed/invalid APK ($len bytes) on v${BuildConfig.VERSION_NAME}")
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
     * Unconditional cache purge (e.g. user manually taps 'Purge Cache' in Settings).
     */
    fun cleanupDownloadedApks(context: Context): Long {
        var totalFreed = 0L
        try {
            context.cacheDir?.listFiles()?.forEach { file ->
                if (file.isFile && (file.name.endsWith(".apk", ignoreCase = true) || file.name.endsWith(".apk.tmp", ignoreCase = true))) {
                    val len = file.length()
                    if (file.delete()) {
                        totalFreed += len
                    }
                }
            }
            context.externalCacheDir?.listFiles()?.forEach { file ->
                if (file.isFile && (file.name.endsWith(".apk", ignoreCase = true) || file.name.endsWith(".apk.tmp", ignoreCase = true))) {
                    val len = file.length()
                    if (file.delete()) {
                        totalFreed += len
                    }
                }
            }
            val updatesDir = File(context.cacheDir, "updates")
            if (updatesDir.exists()) {
                updatesDir.listFiles()?.forEach { f ->
                    if (f.isFile && (f.name.endsWith(".apk", ignoreCase = true) || f.name.endsWith(".apk.tmp", ignoreCase = true))) {
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
        } catch (_: Exception) {}
        return total
    }
}
