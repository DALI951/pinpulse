package com.pinpulse.radar.ui.screens

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.google.gson.Gson
import com.pinpulse.radar.model.User

private const val MAP_TAG = "PinPulseMap"

/**
 * The map page is served from a secure virtual https origin instead of file:///android_asset.
 * Android's Chromium WebView treats file:// pages as an opaque "null" origin, which breaks
 * loading of external map tiles. Serving the same local assets under https gives the page a
 * real web origin — exactly like the Master Web Radar running in a desktop browser.
 */
private const val ASSET_HOST = "appassets.androidplatform.net"
private const val MAP_URL = "https://$ASSET_HOST/assets/map/index.html"

class AndroidMapBridge(
    private val onAvatarClick: (String) -> Unit,
    private val onMapClick: () -> Unit,
    private val onReady: () -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onAvatarClicked(userId: String) {
        handler.post { onAvatarClick(userId) }
    }

    @JavascriptInterface
    fun onMapClicked() {
        handler.post { onMapClick() }
    }

    @JavascriptInterface
    fun onMapReady() {
        handler.post { onReady() }
    }
}

private fun mimeTypeFor(path: String): String = when {
    path.endsWith(".html") -> "text/html"
    path.endsWith(".js") -> "application/javascript"
    path.endsWith(".css") -> "text/css"
    path.endsWith(".png") -> "image/png"
    path.endsWith(".svg") -> "image/svg+xml"
    path.endsWith(".json") -> "application/json"
    else -> "application/octet-stream"
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MapViewComponent(
    visibleUsers: List<User>,
    currentUserId: String?,
    mapStyle: String,
    onAvatarClick: (String) -> Unit,
    onMapClick: () -> Unit,
    centerTarget: User? = null,
    modifier: Modifier = Modifier
) {
    val gson = remember { Gson() }
    // Reactive state: when the WebView is created / the map finishes loading,
    // the effects below re-run and push markers into the map.
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var isMapReady by remember { mutableStateOf(false) }

    val currentOnAvatarClick by rememberUpdatedState(onAvatarClick)
    val currentOnMapClick by rememberUpdatedState(onMapClick)

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    cacheMode = WebSettings.LOAD_DEFAULT
                    mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                }
                setBackgroundColor(android.graphics.Color.parseColor("#0f172a"))

                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
                        message?.let {
                            Log.d(MAP_TAG, "JS: ${it.message()} (${it.sourceId()}:${it.lineNumber()})")
                        }
                        return true
                    }
                }

                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val url = request?.url ?: return null
                        val path = url.path ?: return null
                        if (url.host == ASSET_HOST && path.startsWith("/assets/")) {
                            val assetPath = path.removePrefix("/assets/")
                            return try {
                                WebResourceResponse(
                                    mimeTypeFor(assetPath),
                                    "UTF-8",
                                    ctx.assets.open(assetPath)
                                )
                            } catch (e: Exception) {
                                Log.e(MAP_TAG, "Missing map asset: $assetPath")
                                null
                            }
                        }
                        return null
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        view?.evaluateJavascript("window.resizeMap && window.resizeMap();", null)
                    }
                }

                addJavascriptInterface(
                    AndroidMapBridge(
                        onAvatarClick = { id -> currentOnAvatarClick(id) },
                        onMapClick = { currentOnMapClick() },
                        onReady = {
                            isMapReady = true
                            post { evaluateJavascript("window.resizeMap && window.resizeMap();", null) }
                        }
                    ),
                    "AndroidBridge"
                )
                loadUrl(MAP_URL)
                webViewRef = this
            }
        }
    )

    // Update map style when changed (only once the map exists)
    LaunchedEffect(webViewRef, isMapReady, mapStyle) {
        val webView = webViewRef ?: return@LaunchedEffect
        if (!isMapReady) return@LaunchedEffect
        webView.evaluateJavascript("setMapStyle('$mapStyle');", null)
    }

    // Sync markers whenever the visible users change (adds, updates AND removes stale pins)
    LaunchedEffect(webViewRef, isMapReady, visibleUsers, currentUserId) {
        val webView = webViewRef ?: return@LaunchedEffect
        if (!isMapReady) return@LaunchedEffect
        val userMapData = visibleUsers.map { u ->
            mapOf(
                "id" to u.id,
                "name" to u.displayName,
                "avatar" to (u.avatarBase64 ?: ""),
                "lat" to u.latitude,
                "lng" to u.longitude,
                "speed" to u.speedKmh,
                "isLive" to u.isLive,
                "isSharingLocation" to u.isSharingLocation,
                "isSelf" to (u.id == currentUserId)
            )
        }
        webView.evaluateJavascript("syncMarkers(${gson.toJson(userMapData)});", null)
    }

    // Fly to a person only when explicitly requested (e.g. "center on me" or opening a profile)
    LaunchedEffect(webViewRef, isMapReady, centerTarget) {
        val webView = webViewRef ?: return@LaunchedEffect
        if (!isMapReady) return@LaunchedEffect
        val target = centerTarget ?: return@LaunchedEffect
        if (target.latitude != 0.0 || target.longitude != 0.0) {
            webView.evaluateJavascript("centerOn(${target.latitude}, ${target.longitude}, 16);", null)
        }
    }
}
