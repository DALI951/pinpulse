package com.pinpulse.radar.ui.screens

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.google.gson.Gson
import com.pinpulse.radar.model.User

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
    var webViewRef: WebView? = remember { null }
    var isMapReady = remember { false }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    cacheMode = WebSettings.LOAD_DEFAULT
                    allowFileAccess = true
                    allowContentAccess = true
                    allowFileAccessFromFileURLs = true
                    allowUniversalAccessFromFileURLs = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    databaseEnabled = true
                }
                webChromeClient = android.webkit.WebChromeClient()
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        view?.evaluateJavascript("window.resizeMap && window.resizeMap();", null)
                    }
                }
                addJavascriptInterface(
                    AndroidMapBridge(
                        onAvatarClick = onAvatarClick,
                        onMapClick = onMapClick,
                        onReady = {
                            isMapReady = true
                            post { evaluateJavascript("window.resizeMap && window.resizeMap();", null) }
                        }
                    ),
                    "AndroidBridge"
                )
                loadUrl("file:///android_asset/map/index.html")
                webViewRef = this
            }
        },
        update = { webView ->
            webViewRef = webView
        }
    )

    // Update map style when changed
    LaunchedEffect(mapStyle) {
        webViewRef?.evaluateJavascript("setMapStyle('$mapStyle');", null)
    }

    // Update markers whenever visible users change
    LaunchedEffect(visibleUsers, currentUserId, centerTarget) {
        val webView = webViewRef ?: return@LaunchedEffect
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

        userMapData.forEach { data ->
            val json = gson.toJson(data)
            webView.evaluateJavascript("updateUserMarker($json);", null)
        }

        if (centerTarget != null && centerTarget.latitude != 0.0 && centerTarget.longitude != 0.0) {
            webView.evaluateJavascript("centerOn(${centerTarget.latitude}, ${centerTarget.longitude}, 16);", null)
        } else {
            val self = visibleUsers.find { it.id == currentUserId }
            if (self != null && self.latitude != 0.0 && self.longitude != 0.0) {
                webView.evaluateJavascript("centerOn(${self.latitude}, ${self.longitude}, 16);", null)
            }
        }
    }
}
