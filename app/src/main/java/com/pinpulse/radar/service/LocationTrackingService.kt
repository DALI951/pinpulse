package com.pinpulse.radar.service

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.pinpulse.radar.MainActivity
import com.pinpulse.radar.data.AppPreferences
import com.pinpulse.radar.data.RealtimeService
import com.pinpulse.radar.model.AlertMessage
import com.pinpulse.radar.model.LocationPacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class LocationTrackingService : Service() {

    companion object {
        private const val TAG = "LocationTrackingService"
        private const val NOTIFICATION_ID = 9510
        private const val CHANNEL_ID = "pinpulse_live_tracking"

        const val ACTION_START = "ACTION_START_TRACKING"
        const val ACTION_STOP = "ACTION_STOP_TRACKING"
        const val ACTION_KEEPALIVE_ALARM = "com.pinpulse.radar.ACTION_KEEPALIVE_ALARM"

        fun hasLocationPermission(context: Context): Boolean {
            val hasFine = ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            val hasCoarse = ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            return hasFine || hasCoarse
        }

        fun start(context: Context) {
            val appCtx = context.applicationContext
            val intent = Intent(appCtx, LocationTrackingService::class.java).apply {
                action = ACTION_START
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appCtx.startForegroundService(intent)
                } else {
                    appCtx.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start service safely: ${e.message}")
                scheduleWatchdogAlarm(appCtx, 2000L)
            }
        }

        fun stop(context: Context) {
            val appCtx = context.applicationContext
            val intent = Intent(appCtx, LocationTrackingService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                appCtx.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop service: ${e.message}")
            }
        }

        fun scheduleWatchdogAlarm(context: Context, delayMs: Long = 60_000L) {
            try {
                val appCtx = context.applicationContext
                val alarmManager = appCtx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
                val triggerAt = SystemClock.elapsedRealtime() + delayMs

                // 1. Broadcast alarm to BootReceiver (works reliably across all Android versions in Doze)
                val receiverIntent = Intent(appCtx, BootReceiver::class.java).apply {
                    action = ACTION_KEEPALIVE_ALARM
                    setPackage(appCtx.packageName)
                }
                val receiverPending = PendingIntent.getBroadcast(
                    appCtx,
                    9511,
                    receiverIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        alarmManager.setExactAndAllowWhileIdle(
                            AlarmManager.ELAPSED_REALTIME_WAKEUP,
                            triggerAt,
                            receiverPending
                        )
                    } catch (se: SecurityException) {
                        alarmManager.setAndAllowWhileIdle(
                            AlarmManager.ELAPSED_REALTIME_WAKEUP,
                            triggerAt,
                            receiverPending
                        )
                    }
                } else {
                    alarmManager.setExact(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        receiverPending
                    )
                }

                // 2. Direct ForegroundService PendingIntent safety net for short restarts
                if (delayMs <= 5000L) {
                    val serviceIntent = Intent(appCtx, LocationTrackingService::class.java).apply {
                        action = ACTION_START
                        setPackage(appCtx.packageName)
                    }
                    val servicePending = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        PendingIntent.getForegroundService(
                            appCtx,
                            9512,
                            serviceIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                    } else {
                        PendingIntent.getService(
                            appCtx,
                            9512,
                            serviceIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        alarmManager.setAndAllowWhileIdle(
                            AlarmManager.ELAPSED_REALTIME_WAKEUP,
                            triggerAt + 500L,
                            servicePending
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to schedule watchdog alarm: ${e.message}")
            }
        }

        fun getBatteryInfo(context: Context): Pair<Int, Boolean> {
            return try {
                val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                val batteryStatus = context.applicationContext.registerReceiver(null, ifilter)
                val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
                val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else 100
                Pair(pct, isCharging)
            } catch (e: Exception) {
                Pair(100, false)
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationManager: LocationManager
    private lateinit var locationCallback: LocationCallback
    private lateinit var nativeLocationListener: LocationListener
    private lateinit var prefs: AppPreferences
    private lateinit var realtimeService: RealtimeService

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var heartbeatJob: Job? = null
    private var isListenersRegistered = false
    private var isExplicitlyStopped = false

    @Volatile
    private var lastLocationElapsedRealtime: Long = 0L

    @Volatile
    private var lastDataServicePollRealtime: Long = 0L

    @Volatile
    private var hasExactGpsOrCellFix: Boolean = false

    @Volatile
    private var lastDataServiceIp: String = ""

    override fun onCreate() {
        super.onCreate()
        prefs = AppPreferences(this)
        realtimeService = RealtimeService.getInstance(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        createNotificationChannel()
        ensureWakeAndWifiLocks()

        // If user already had saved coordinates on disk, preserve them initially
        val existingUser = prefs.loadCurrentUser()
        if (existingUser != null && (existingUser.latitude != 0.0 || existingUser.longitude != 0.0)) {
            hasExactGpsOrCellFix = true
        }

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                lastLocationElapsedRealtime = SystemClock.elapsedRealtime()
                hasExactGpsOrCellFix = true
                handleNewLocation(location)
            }
        }

        nativeLocationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                lastLocationElapsedRealtime = SystemClock.elapsedRealtime()
                hasExactGpsOrCellFix = true
                handleNewLocation(location)
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
    }

    @Suppress("DEPRECATION")
    private fun ensureWakeAndWifiLocks() {
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "PinPulse::TrackingWakeLock"
                ).apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld != true) {
                wakeLock?.acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock acquire error: ${e.message}")
        }

        try {
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val lockType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wm?.createWifiLock(lockType, "PinPulse::TrackingWifiLock")?.apply {
                    setReferenceCounted(false)
                }
            }
            if (wifiLock?.isHeld != true) {
                wifiLock?.acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "WifiLock acquire error: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                isExplicitlyStopped = true
                handleStopTracking()
                stopSelf()
            }
            else -> {
                isExplicitlyStopped = false
                startForegroundTracking()
            }
        }
        return START_STICKY
    }

    private fun startForegroundTracking() {
        val notification = buildNotification("24/7 Live Radar Active — Sharing location")
        var startedFgs = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (hasLocationPermission(this)) {
                try {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                    )
                    startedFgs = true
                } catch (e: Exception) {
                    Log.w(TAG, "LOCATION FGS type unavailable (${e.message}), falling back to DATA_SYNC")
                }
            }
            if (!startedFgs) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        startForeground(
                            NOTIFICATION_ID,
                            notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                        )
                    } else {
                        startForeground(NOTIFICATION_ID, notification)
                    }
                    startedFgs = true
                } catch (e: Exception) {
                    Log.e(TAG, "Fallback FGS start error: ${e.message}")
                }
            }
        } else {
            try {
                startForeground(NOTIFICATION_ID, notification)
                startedFgs = true
            } catch (e: Exception) {
                Log.e(TAG, "startForeground error: ${e.message}")
            }
        }

        if (!startedFgs) return

        ensureWakeAndWifiLocks()
        scheduleWatchdogAlarm(this, 60_000L)

        val currentUser = prefs.loadCurrentUser()
        if (currentUser != null) {
            val activeUser = if (!currentUser.isSharingLocation) {
                val fixed = currentUser.copy(isSharingLocation = true)
                prefs.saveCurrentUser(fixed)
                fixed
            } else {
                currentUser
            }
            realtimeService.ensureConnected(activeUser)
        }

        if (!isListenersRegistered && hasLocationPermission(this)) {
            requestLocationUpdates()
        }

        pollBestAvailableLocation()

        // Only launch heartbeat coroutine if not already active (prevents resetting 3s loop on Activity resume)
        if (heartbeatJob?.isActive != true) {
            heartbeatJob = serviceScope.launch {
                var tickCount = 0
                while (isActive) {
                    delay(3000L)
                    tickCount++
                    try {
                        ensureWakeAndWifiLocks()

                        val user = prefs.loadCurrentUser()
                        if (user != null) {
                            // Ensure MQTT stays connected 24/7 even across network switches or idle periods
                            realtimeService.ensureConnected(user)

                            // If no GPS callback arrived in the last 10 seconds, actively poll providers
                            if (tickCount % 4 == 0 && (SystemClock.elapsedRealtime() - lastLocationElapsedRealtime > 10_000L)) {
                                pollBestAvailableLocation()
                            }

                            // Every 60 seconds, refresh watchdog alarm and re-assert location listeners
                            if (tickCount % 20 == 0) {
                                scheduleWatchdogAlarm(this@LocationTrackingService, 60_000L)
                                if (hasLocationPermission(this@LocationTrackingService)) {
                                    requestLocationUpdates()
                                }
                            }

                            if (user.latitude != 0.0 || user.longitude != 0.0) {
                                val (batteryPct, isCharging) = getBatteryInfo(this@LocationTrackingService)
                                val now = System.currentTimeMillis()
                                val updatedUser = user.copy(
                                    batteryPercent = batteryPct,
                                    isCharging = isCharging,
                                    isSharingLocation = true,
                                    lastUpdated = now
                                )
                                prefs.saveCurrentUser(updatedUser)

                                val packet = LocationPacket(
                                    userId = updatedUser.id,
                                    displayName = updatedUser.displayName,
                                    email = updatedUser.email,
                                    friendCode = updatedUser.friendCode,
                                    avatarBase64 = updatedUser.avatarBase64,
                                    statusMessage = updatedUser.statusMessage,
                                    latitude = updatedUser.latitude,
                                    longitude = updatedUser.longitude,
                                    speedKmh = updatedUser.speedKmh,
                                    batteryPercent = batteryPct,
                                    isCharging = isCharging,
                                    isSharingLocation = true,
                                    timestamp = now
                                )
                                realtimeService.publishLocation(packet)
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Heartbeat tick error: ${e.message}")
                    }
                }
            }
        }
    }

    private fun isSystemLocationEnabled(): Boolean {
        return try {
            val gps = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            val net = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            gps || net
        } catch (e: Exception) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun pollBestAvailableLocation() {
        val hasPerm = hasLocationPermission(this)
        val sysEnabled = isSystemLocationEnabled()

        if (hasPerm && sysEnabled) {
            try {
                fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                    if (loc != null) {
                        hasExactGpsOrCellFix = true
                        handleNewLocation(loc)
                    }
                }
                fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                    .addOnSuccessListener { loc ->
                        if (loc != null) {
                            hasExactGpsOrCellFix = true
                            handleNewLocation(loc)
                        }
                    }
            } catch (e: Exception) {
                Log.w(TAG, "Fused poll error: ${e.message}")
            }

            try {
                val gpsLoc = if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                } else null
                val netLoc = if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                } else null
                val passiveLoc = if (locationManager.allProviders.contains(LocationManager.PASSIVE_PROVIDER)) {
                    locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
                } else null

                val bestNative = listOfNotNull(gpsLoc, netLoc, passiveLoc).maxByOrNull { it.time }
                if (bestNative != null) {
                    hasExactGpsOrCellFix = true
                    handleNewLocation(bestNative)
                    return
                }
            } catch (e: Exception) {
                Log.w(TAG, "Native location poll error: ${e.message}")
            }
        } else if (hasPerm) {
            // Even if GPS/Network toggle was just turned off, check cached last known location in OS
            try {
                val cachedGps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                val cachedNet = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                val cachedPassive = locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
                val bestCached = listOfNotNull(cachedGps, cachedNet, cachedPassive).maxByOrNull { it.time }
                if (bestCached != null) {
                    hasExactGpsOrCellFix = true
                    handleNewLocation(bestCached)
                }
            } catch (e: Exception) {}
        }

        // Silent Data Service (Mobile Data / Wi-Fi Carrier IP) Fallback when GPS is OFF or no fix yet!
        val currentUser = prefs.loadCurrentUser()
        val hasNoCoordinatesYet = currentUser == null || (currentUser.latitude == 0.0 && currentUser.longitude == 0.0)
        val nowRealtime = SystemClock.elapsedRealtime()
        val pollIntervalMs = if (hasNoCoordinatesYet) 10_000L else 45_000L

        if ((!sysEnabled || !hasPerm || hasNoCoordinatesYet) &&
            (lastDataServicePollRealtime == 0L || (nowRealtime - lastDataServicePollRealtime) >= pollIntervalMs)
        ) {
            lastDataServicePollRealtime = nowRealtime
            serviceScope.launch {
                pollDataServiceLocationFallback()
            }
        }
    }

    /**
     * Silently resolves approximate coordinates via Mobile Data (4G/5G carrier) or Wi-Fi internet
     * without ever bothering the user or turning on GPS.
     */
    private fun pollDataServiceLocationFallback() {
        val endpoints = listOf(
            "https://ipwho.is/",
            "https://ipapi.co/json/",
            "https://freeipapi.com/api/json",
            "http://ip-api.com/json/?fields=status,query,lat,lon"
        )

        for (endpoint in endpoints) {
            try {
                val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 5000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "PinPulse-Android")
                    setRequestProperty("Accept", "application/json")
                }
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(body)

                    // Validate provider success flags if present
                    if (json.has("success") && !json.optBoolean("success", true)) continue
                    if (json.has("status") && !json.optString("status", "success").equals("success", ignoreCase = true)) continue

                    val lat = when {
                        json.has("latitude") -> json.optDouble("latitude", 0.0)
                        json.has("lat") -> json.optDouble("lat", 0.0)
                        else -> 0.0
                    }
                    val lon = when {
                        json.has("longitude") -> json.optDouble("longitude", 0.0)
                        json.has("lon") -> json.optDouble("lon", 0.0)
                        else -> 0.0
                    }
                    val ip = when {
                        json.has("ip") -> json.optString("ip", "")
                        json.has("ipAddress") -> json.optString("ipAddress", "")
                        json.has("query") -> json.optString("query", "")
                        else -> ""
                    }

                    if (lat != 0.0 || lon != 0.0) {
                        applyDataServiceLocation(lat, lon, ip)
                        return
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Data service geolocation fallback ($endpoint) skipped: ${e.message}")
            }
        }
    }

    private fun applyDataServiceLocation(lat: Double, lon: Double, ip: String) {
        val currentUser = prefs.loadCurrentUser() ?: return
        val hasExistingCoords = currentUser.latitude != 0.0 || currentUser.longitude != 0.0

        val shouldUpdateCoordinates = if (!hasExistingCoords || !hasExactGpsOrCellFix) {
            // User had no coordinates or only had a previous Data Service coordinate -> use new Data Service coordinate
            true
        } else {
            // User already has an exact street-level GPS/Cell fix.
            // Only move their pin to the coarse Data Service coordinate if they traveled > 15 km to a new network/city while GPS was off!
            val distResults = FloatArray(1)
            Location.distanceBetween(currentUser.latitude, currentUser.longitude, lat, lon, distResults)
            val distanceMeters = distResults[0]
            val ipChanged = ip.isNotBlank() && lastDataServiceIp.isNotBlank() && ip != lastDataServiceIp
            distanceMeters > 15_000f && (ipChanged || lastDataServiceIp.isBlank())
        }

        if (ip.isNotBlank()) {
            lastDataServiceIp = ip
        }

        val finalLat = if (shouldUpdateCoordinates) lat else currentUser.latitude
        val finalLon = if (shouldUpdateCoordinates) lon else currentUser.longitude
        if (finalLat == 0.0 && finalLon == 0.0) return

        val (batteryPct, isCharging) = getBatteryInfo()
        val now = System.currentTimeMillis()

        val updatedUser = currentUser.copy(
            latitude = finalLat,
            longitude = finalLon,
            speedKmh = if (shouldUpdateCoordinates) 0f else currentUser.speedKmh,
            batteryPercent = batteryPct,
            isCharging = isCharging,
            isSharingLocation = true,
            lastUpdated = now
        )
        prefs.saveCurrentUser(updatedUser)

        val packet = LocationPacket(
            userId = updatedUser.id,
            displayName = updatedUser.displayName,
            email = updatedUser.email,
            friendCode = updatedUser.friendCode,
            avatarBase64 = updatedUser.avatarBase64,
            statusMessage = updatedUser.statusMessage,
            latitude = finalLat,
            longitude = finalLon,
            speedKmh = updatedUser.speedKmh,
            batteryPercent = batteryPct,
            isCharging = isCharging,
            isSharingLocation = true,
            timestamp = now
        )
        realtimeService.publishLocation(packet)
    }

    @SuppressLint("MissingPermission")
    private fun requestLocationUpdates() {
        if (!hasLocationPermission(this)) return

        try {
            val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2500L)
                .setMinUpdateIntervalMillis(1500L)
                .setMaxUpdateDelayMillis(3000L)
                .setMinUpdateDistanceMeters(0f)
                .setWaitForAccurateLocation(false)
                .build()

            fusedLocationClient.removeLocationUpdates(locationCallback)
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
            isListenersRegistered = true
            Log.d(TAG, "FusedLocation updates registered (2.5s interval)")
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting fused location: ${e.message}")
        }

        // Register native hardware GPS & Network listeners on main looper as Doze-proof fallback
        try {
            locationManager.removeUpdates(nativeLocationListener)
            if (locationManager.allProviders.contains(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    3000L,
                    0f,
                    nativeLocationListener,
                    Looper.getMainLooper()
                )
            }
            if (locationManager.allProviders.contains(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    3000L,
                    0f,
                    nativeLocationListener,
                    Looper.getMainLooper()
                )
            }
            if (locationManager.allProviders.contains(LocationManager.PASSIVE_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.PASSIVE_PROVIDER,
                    2000L,
                    0f,
                    nativeLocationListener,
                    Looper.getMainLooper()
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Native LocationManager registration warning: ${e.message}")
        }
    }

    private fun getBatteryInfo(): Pair<Int, Boolean> = getBatteryInfo(this)

    private fun handleNewLocation(location: Location) {
        if (location.latitude == 0.0 && location.longitude == 0.0) return
        val currentUser = prefs.loadCurrentUser() ?: return
        val speedKmh = (location.speed * 3.6f).coerceAtLeast(0f)
        val (batteryPct, isCharging) = getBatteryInfo()
        val now = System.currentTimeMillis()

        val updatedUser = currentUser.copy(
            latitude = location.latitude,
            longitude = location.longitude,
            speedKmh = speedKmh,
            batteryPercent = batteryPct,
            isCharging = isCharging,
            isSharingLocation = true,
            lastUpdated = now
        )
        prefs.saveCurrentUser(updatedUser)

        val packet = LocationPacket(
            userId = updatedUser.id,
            displayName = updatedUser.displayName,
            email = updatedUser.email,
            friendCode = updatedUser.friendCode,
            avatarBase64 = updatedUser.avatarBase64,
            statusMessage = updatedUser.statusMessage,
            latitude = location.latitude,
            longitude = location.longitude,
            speedKmh = speedKmh,
            batteryPercent = batteryPct,
            isCharging = isCharging,
            isSharingLocation = true,
            timestamp = now
        )
        realtimeService.publishLocation(packet)
    }

    private fun handleStopTracking() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        isListenersRegistered = false
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Error removing fused location updates: ${e.message}")
        }
        try {
            locationManager.removeUpdates(nativeLocationListener)
        } catch (e: Exception) {}

        val currentUser = prefs.loadCurrentUser()
        if (currentUser != null) {
            val (batteryPct, isCharging) = getBatteryInfo()
            val updatedUser = currentUser.copy(
                isSharingLocation = false,
                batteryPercent = batteryPct,
                isCharging = isCharging
            )
            prefs.saveCurrentUser(updatedUser)

            val packet = LocationPacket(
                userId = updatedUser.id,
                displayName = updatedUser.displayName,
                email = updatedUser.email,
                friendCode = updatedUser.friendCode,
                avatarBase64 = updatedUser.avatarBase64,
                statusMessage = updatedUser.statusMessage,
                latitude = updatedUser.latitude,
                longitude = updatedUser.longitude,
                speedKmh = 0f,
                batteryPercent = batteryPct,
                isCharging = isCharging,
                isSharingLocation = false,
                timestamp = System.currentTimeMillis()
            )
            realtimeService.publishLocation(packet)

            // Broadcast immediate alert to all friends and all groups
            val alert = AlertMessage(
                senderId = currentUser.id,
                senderName = currentUser.displayName,
                type = "LOCATION_OFF",
                message = "${currentUser.displayName} turned off their live location sharing."
            )

            // Alert 1-on-1 friends
            prefs.loadFriends().forEach { friend ->
                realtimeService.broadcastAlert(friend.friendCode, alert)
            }

            // Alert all group channels
            prefs.loadGroups().forEach { group ->
                realtimeService.publish("pinpulse/v1/groups/${group.code}/events", com.google.gson.Gson().toJson(alert))
            }
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PinPulse Radar Active (24/7)")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "PinPulse Live Tracking",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps PinPulse radar sharing your live location 24/7 in background"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val currentUser = prefs.loadCurrentUser()
        if (currentUser != null && !isExplicitlyStopped) {
            Log.d(TAG, "Task removed from Recents — preserving 24/7 background location tracking!")
            scheduleWatchdogAlarm(applicationContext, 1500L)
            startForegroundTracking()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        heartbeatJob?.cancel()
        heartbeatJob = null
        isListenersRegistered = false
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        } catch (e: Exception) {}
        try {
            locationManager.removeUpdates(nativeLocationListener)
        } catch (e: Exception) {}
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {}
        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
        } catch (e: Exception) {}

        // If OS killed the service unexpectedly while user is logged in, immediately schedule revival!
        if (!isExplicitlyStopped && prefs.loadCurrentUser() != null) {
            Log.w(TAG, "Service destroyed unexpectedly — scheduling immediate watchdog revival!")
            scheduleWatchdogAlarm(applicationContext, 1500L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
