package com.pinpulse.radar.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.util.Base64
import android.util.Log
import androidx.collection.LruCache
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import coil.Coil
import coil.compose.AsyncImage
import coil.request.ImageRequest

object ImageHelper {

    private const val TAG = "ImageHelper"

    // In-memory cache for map marker Bitmaps (holds up to 50 decoded avatar bitmaps)
    private val bitmapCache = LruCache<String, Bitmap>(50)

    /**
     * Parses an avatar source string (which may be a data URI like "data:image/jpeg;base64,...",
     * raw Base64, or an HTTP/HTTPS URL) into a model that Coil's AsyncImage can consume natively.
     * Coil natively accepts ByteArray for raw binary images, or String/Uri for web URLs.
     */
    fun parseAvatarModel(avatar: String?): Any? {
        if (avatar.isNullOrBlank()) return null

        val trimmed = avatar.trim()
        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            return trimmed
        }

        return try {
            val clean = if (trimmed.contains(",")) trimmed.substringAfter(",") else trimmed
            Base64.decode(clean, Base64.DEFAULT)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode base64 avatar: ${e.message}")
            null
        }
    }

    /**
     * Synchronously decodes a Base64 string to a Bitmap (or returns from memory cache).
     * If [avatarStr] is an HTTP URL, asynchronously fetches it using Coil and calls [onLoaded].
     */
    fun getOrDecodeBitmap(
        context: Context,
        avatarStr: String?,
        onLoaded: (() -> Unit)? = null
    ): Bitmap? {
        if (avatarStr.isNullOrBlank()) return null

        val trimmed = avatarStr.trim()

        // 1. Check memory cache first
        bitmapCache.get(trimmed)?.let { return it }

        // 2. HTTP/HTTPS URL -> asynchronous fetch via Coil
        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            val imageLoader = Coil.imageLoader(context)
            val request = ImageRequest.Builder(context)
                .data(trimmed)
                .allowHardware(false) // Hardware bitmaps cannot be drawn to custom Canvases
                .target(object : coil.target.Target {
                    override fun onSuccess(result: android.graphics.drawable.Drawable) {
                        val bmp = (result as? BitmapDrawable)?.bitmap
                        if (bmp != null) {
                            bitmapCache.put(trimmed, bmp)
                            onLoaded?.invoke()
                        }
                    }
                })
                .build()
            imageLoader.enqueue(request)
            return null
        }

        // 3. Base64 encoded string -> decode directly
        return try {
            val clean = if (trimmed.contains(",")) trimmed.substringAfter(",") else trimmed
            val bytes = Base64.decode(clean, Base64.DEFAULT)
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bmp != null) {
                bitmapCache.put(trimmed, bmp)
            }
            bmp
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding avatar to Bitmap: ${e.message}")
            null
        }
    }

    /**
     * Clears cached avatar bitmaps (e.g. after updating personal photo).
     */
    fun evictFromCache(avatarStr: String?) {
        if (!avatarStr.isNullOrBlank()) {
            bitmapCache.remove(avatarStr.trim())
        }
    }
}

/**
 * Universal Composable for rendering user avatars across all screens.
 * Supports Base64 data URIs, raw Base64 strings, and HTTP/HTTPS URLs.
 * If no avatar is available or loading fails, displays a clean fallback initial letter.
 */
@Composable
fun UserAvatar(
    avatarSource: String?,
    displayName: String,
    modifier: Modifier = Modifier,
    contentDescription: String = displayName,
    fallbackTextSize: TextUnit = 16.sp,
    fallbackColor: Color = Color.White
) {
    val model = remember(avatarSource) { ImageHelper.parseAvatarModel(avatarSource) }
    var hasError by remember(avatarSource) { mutableStateOf(false) }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        if (model != null && !hasError) {
            AsyncImage(
                model = model,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onError = {
                    hasError = true
                }
            )
        } else {
            val initial = displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            Text(
                text = initial,
                color = fallbackColor,
                fontWeight = FontWeight.Bold,
                fontSize = fallbackTextSize
            )
        }
    }
}
