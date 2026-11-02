package com.pinpulse.radar.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.text.TextPaint
import android.text.TextUtils
import android.util.Base64
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.pinpulse.radar.model.User
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import java.io.File

// Native Google Hybrid Tile Source for OSMDroid
val GoogleHybridTileSource = object : OnlineTileSourceBase(
    "GoogleHybrid", 0, 20, 256, ".png",
    arrayOf(
        "https://mt0.google.com/vt/lyrs=y&",
        "https://mt1.google.com/vt/lyrs=y&",
        "https://mt2.google.com/vt/lyrs=y&",
        "https://mt3.google.com/vt/lyrs=y&"
    )
) {
    override fun getTileURLString(pMapTileIndex: Long): String {
        val z = MapTileIndex.getZoom(pMapTileIndex)
        val x = MapTileIndex.getX(pMapTileIndex)
        val y = MapTileIndex.getY(pMapTileIndex)
        return "${baseUrl}x=$x&y=$y&z=$z"
    }
}

// Native Google Satellite Tile Source for OSMDroid
val GoogleSatelliteTileSource = object : OnlineTileSourceBase(
    "GoogleSatellite", 0, 20, 256, ".png",
    arrayOf(
        "https://mt0.google.com/vt/lyrs=s&",
        "https://mt1.google.com/vt/lyrs=s&",
        "https://mt2.google.com/vt/lyrs=s&",
        "https://mt3.google.com/vt/lyrs=s&"
    )
) {
    override fun getTileURLString(pMapTileIndex: Long): String {
        val z = MapTileIndex.getZoom(pMapTileIndex)
        val x = MapTileIndex.getX(pMapTileIndex)
        val y = MapTileIndex.getY(pMapTileIndex)
        return "${baseUrl}x=$x&y=$y&z=$z"
    }
}

@Composable
fun MapViewComponent(
    visibleUsers: List<User>,
    currentUserId: String?,
    mapStyle: String,
    selfMarkerMode: String = "FULL",
    onAvatarClick: (String) -> Unit,
    onMapClick: () -> Unit,
    centerTarget: User? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Initialize OSMDroid native configuration
    remember {
        Configuration.getInstance().apply {
            userAgentValue = context.packageName
            osmdroidBasePath = File(context.cacheDir, "osmdroid")
            osmdroidTileCache = File(context.cacheDir, "osmdroid/tiles")
        }
    }

    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var hasAutoCentered by remember { mutableStateOf(false) }

    val currentOnAvatarClick by rememberUpdatedState(onAvatarClick)
    val currentOnMapClick by rememberUpdatedState(onMapClick)

    // Manage native MapView lifecycle
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapViewRef?.onResume()
                Lifecycle.Event.ON_PAUSE -> mapViewRef?.onPause()
                Lifecycle.Event.ON_DESTROY -> mapViewRef?.onDetach()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapViewRef?.onDetach()
        }
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            MapView(ctx).apply {
                setMultiTouchControls(true)
                isTilesScaledToDpi = true
                minZoomLevel = 3.0
                maxZoomLevel = 20.0
                zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)

                // Default style: OpenStreetMap Mapnik
                setTileSource(TileSourceFactory.MAPNIK)
                controller.setZoom(15.0)
                controller.setCenter(GeoPoint(36.8065, 10.1815))

                // Single tap on map background dismisses detail sheets
                val clickEventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
                    override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                        currentOnMapClick()
                        return true
                    }
                    override fun longPressHelper(p: GeoPoint?): Boolean = false
                })
                overlays.add(0, clickEventsOverlay)

                mapViewRef = this
            }
        }
    )

    // Switch native tile sources when mapStyle changes
    LaunchedEffect(mapViewRef, mapStyle) {
        val mapView = mapViewRef ?: return@LaunchedEffect
        val tileSource = when (mapStyle) {
            "satellite" -> GoogleSatelliteTileSource
            "hybrid" -> GoogleHybridTileSource
            else -> TileSourceFactory.MAPNIK
        }
        mapView.setTileSource(tileSource)
        mapView.invalidate()
    }

    // Sync native user markers
    LaunchedEffect(mapViewRef, visibleUsers, currentUserId, selfMarkerMode) {
        val mapView = mapViewRef ?: return@LaunchedEffect

        // Keep the click overlay at index 0, remove other markers
        val eventsOverlay = mapView.overlays.firstOrNull { it is MapEventsOverlay }
        mapView.overlays.clear()
        eventsOverlay?.let { mapView.overlays.add(it) }

        visibleUsers.forEach { user ->
            if (user.latitude == 0.0 && user.longitude == 0.0) return@forEach

            val isSelf = (user.id == currentUserId)

            // If user chose to remove themselves from the map, don't draw self marker
            if (isSelf && selfMarkerMode.equals("HIDDEN", ignoreCase = true)) {
                return@forEach
            }

            val isDotMode = isSelf && selfMarkerMode.equals("DOT", ignoreCase = true)

            val markerIcon = if (isDotMode) {
                BitmapDrawable(context.resources, createSelfDotBitmap(context, user.isLive))
            } else {
                BitmapDrawable(
                    context.resources,
                    createMarkerBitmap(
                        context = context,
                        name = user.displayName,
                        avatarBase64 = user.avatarBase64,
                        batteryPercent = user.batteryPercent,
                        isCharging = user.isCharging,
                        isSelf = isSelf,
                        isLive = user.isLive
                    )
                )
            }

            val marker = Marker(mapView).apply {
                position = GeoPoint(user.latitude, user.longitude)
                setAnchor(Marker.ANCHOR_CENTER, if (isDotMode) Marker.ANCHOR_CENTER else 0.45f)
                infoWindow = null // No default popup bubble
                icon = markerIcon
                setOnMarkerClickListener { _, _ ->
                    currentOnAvatarClick(user.id)
                    true
                }
            }
            mapView.overlays.add(marker)
        }

        // Auto-center on self on first GPS fix
        if (!hasAutoCentered) {
            val self = visibleUsers.find { it.id == currentUserId }
            if (self != null && (self.latitude != 0.0 || self.longitude != 0.0)) {
                mapView.controller.animateTo(GeoPoint(self.latitude, self.longitude), 16.0, 800L)
                hasAutoCentered = true
            }
        }

        mapView.invalidate()
    }

    // Animate to target person when explicitly selected
    LaunchedEffect(mapViewRef, centerTarget) {
        val mapView = mapViewRef ?: return@LaunchedEffect
        val target = centerTarget ?: return@LaunchedEffect
        if (target.latitude != 0.0 || target.longitude != 0.0) {
            mapView.controller.animateTo(GeoPoint(target.latitude, target.longitude), 16.5, 900L)
        }
    }
}

private fun decodeBase64ToBitmap(base64Str: String): Bitmap? {
    return try {
        val clean = if (base64Str.contains(",")) base64Str.substringAfter(",") else base64Str
        val bytes = Base64.decode(clean, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (e: Exception) {
        null
    }
}

private fun createSelfDotBitmap(context: Context, isLive: Boolean): Bitmap {
    val density = context.resources.displayMetrics.density
    val size = (24 * density).toInt().coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val center = size / 2f

    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Outer subtle glow halo
    paint.style = Paint.Style.FILL
    paint.color = if (isLive) 0x553B82F6.toInt() else 0x3364748B.toInt()
    canvas.drawCircle(center, center - (1 * density), paint)

    // Solid core dot
    paint.color = if (isLive) 0xFF3B82F6.toInt() else 0xFF64748B.toInt()
    canvas.drawCircle(center, 6.5f * density, paint)

    // Crisp white border
    paint.color = Color.WHITE
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 2f * density
    canvas.drawCircle(center, 6.5f * density, paint)

    return bitmap
}

private fun createMarkerBitmap(
    context: Context,
    name: String,
    avatarBase64: String?,
    batteryPercent: Int,
    isCharging: Boolean,
    isSelf: Boolean,
    isLive: Boolean
): Bitmap {
    val density = context.resources.displayMetrics.density
    val width = (100 * density).toInt()
    val height = (100 * density).toInt()
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val centerX = width / 2f
    val avatarRadius = 22 * density
    val avatarCenterY = avatarRadius + (4 * density)

    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    // 1. Live pulse glow ring
    if (isLive) {
        paint.color = if (isSelf) 0x663B82F6.toInt() else 0x6610B981.toInt()
        paint.style = Paint.Style.FILL
        canvas.drawCircle(centerX, avatarCenterY, avatarRadius + (6 * density), paint)
    }

    // 2. Avatar background or decoded photo
    val avatarBitmap = decodeBase64ToBitmap(avatarBase64 ?: "")
    if (avatarBitmap != null) {
        val scaled = Bitmap.createScaledBitmap(avatarBitmap, (avatarRadius * 2).toInt(), (avatarRadius * 2).toInt(), true)
        val shader = BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val matrix = Matrix().apply {
            setTranslate(centerX - avatarRadius, avatarCenterY - avatarRadius)
        }
        shader.setLocalMatrix(matrix)
        paint.shader = shader
        paint.style = Paint.Style.FILL
        canvas.drawCircle(centerX, avatarCenterY, avatarRadius, paint)
        paint.shader = null
    } else {
        paint.color = 0xFF1E293B.toInt() // Slate 800
        paint.style = Paint.Style.FILL
        canvas.drawCircle(centerX, avatarCenterY, avatarRadius, paint)

        paint.color = Color.WHITE
        paint.textSize = 17 * density
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textAlign = Paint.Align.CENTER
        val initial = name.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        val textY = avatarCenterY - ((paint.descent() + paint.ascent()) / 2)
        canvas.drawText(initial, centerX, textY, paint)
    }

    // 3. Colored border ring around avatar
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 3 * density
    paint.color = when {
        isSelf -> 0xFF3B82F6.toInt()
        isLive -> 0xFF10B981.toInt()
        else -> 0xFF64748B.toInt()
    }
    canvas.drawCircle(centerX, avatarCenterY, avatarRadius, paint)

    // 4. Pill badge with Name and live Battery %
    val label = buildString {
        if (isSelf) append("$name (You)") else append(name)
        append(" ($batteryPercent%${if (isCharging) " ⚡" else ""})")
    }

    paint.style = Paint.Style.FILL
    paint.textSize = 10 * density
    paint.typeface = Typeface.DEFAULT_BOLD
    paint.textAlign = Paint.Align.CENTER

    val textWidth = paint.measureText(label)
    val pillPaddingH = 7 * density
    val pillPaddingV = 3.5f * density
    val pillHeight = (paint.descent() - paint.ascent()) + (pillPaddingV * 2)
    val pillWidth = (textWidth + (pillPaddingH * 2)).coerceAtMost(width.toFloat() - (4 * density))
    val pillTop = avatarCenterY + avatarRadius + (3 * density)
    val pillRect = RectF(
        centerX - (pillWidth / 2f),
        pillTop,
        centerX + (pillWidth / 2f),
        pillTop + pillHeight
    )

    // Pill background
    paint.color = 0xEA0F172A.toInt() // Dark Navy 92%
    paint.style = Paint.Style.FILL
    canvas.drawRoundRect(pillRect, 8 * density, 8 * density, paint)

    // Pill border
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 1 * density
    paint.color = if (isSelf) 0x883B82F6.toInt() else 0x33FFFFFF.toInt()
    canvas.drawRoundRect(pillRect, 8 * density, 8 * density, paint)

    // Pill text
    paint.style = Paint.Style.FILL
    paint.color = if (isSelf) 0xFF93C5FD.toInt() else 0xFFF8FAFC.toInt()
    val pillTextY = pillRect.centerY() - ((paint.descent() + paint.ascent()) / 2)
    val elided = TextUtils.ellipsize(
        label,
        TextPaint(paint),
        pillWidth - (pillPaddingH * 2),
        TextUtils.TruncateAt.END
    ).toString()
    canvas.drawText(elided, centerX, pillTextY, paint)

    return bitmap
}
