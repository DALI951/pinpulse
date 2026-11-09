package com.pinpulse.radar.service

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
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
import com.pinpulse.radar.model.User
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

        private val backgroundPulseScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        @Volatile
        private var isServiceRunningInForeground = false

        @Volatile
        private var activeInstance: LocationTrackingService? = null

        @Volatile
        var lastWatchTimestamp: Long = 0L
            private set

        fun onViewerWatching() {
            lastWatchTimestamp = System.currentTimeMillis()
            activeInstance?.checkAndApplyTrackingMode()
        }

        fun hasLocationPermission(context: Context): Boolean {
            val hasFine = ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            val hasCoarse = ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            return hasFine || hasCoarse
        }

        fun hasBackgroundLocationPermission(context: Context): Boolean {
            if (!hasLocationPermission(context)) return false
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        }

        fun isAppInForeground(context: Context): Boolean {
            return try {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
                val processes = am.runningAppProcesses ?: return false
                val pkg = context.packageName
                processes.any {
                    it.processName == pkg &&
                        it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
                }
            } catch (e: Exception) {
                false
            }
        }

        fun start(context: Context) {
            val appCtx = context.applicationContext
            val currentUser = AppPreferences(appCtx).loadCurrentUser() ?: return

            // 1. If the Foreground Service is ALREADY running, do NOT re-call startForegroundService()
            // (prevents Android 14/15 One UI 7.0 background FGS re-verification crashes).
            val existing = activeInstance
            if (isServiceRunningInForeground && existing != null) {
                existing.pokeFromExternalTrigger()
                return
            }

            // 2. Check if Android 14/15 allows starting a Foreground Service right now:
            // Allowed if the app is currently open in the foreground OR user granted "Allow all the time" (ACCESS_BACKGROUND_LOCATION).
            val inForeground = isAppInForeground(appCtx)
            val hasBgLoc = hasBackgroundLocationPermission(appCtx)

            if (!inForeground && !hasBgLoc) {
                // App is in background without "Allow all the time" permission:
                // Never call startForegroundService() (which crashes Android 15 / One UI 7.0);
                // instead perform a silent background location & MQTT pulse directly!
                Log.d(TAG, "Background pulse mode (no ACCESS_BACKGROUND_LOCATION yet) — sending silent pulse")
                performSilentBackgroundPulse(appCtx)
                scheduleWatchdogAlarm(appCtx, 60_000L)
                return
            }

            val intent = Intent(appCtx, LocationTrackingService::class.java).apply {
                action = ACTION_START
            }
            try {
                if (inForeground) {
                    // When in foreground, startService() avoids arming the strict 5s startForegroundService() crash timer
                    try {
                        appCtx.startService(intent)
                        return
                    } catch (_: Exception) {}
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appCtx.startForegroundService(intent)
                } else {
                    appCtx.startService(intent)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "FGS start deferred (${t.message}) — running silent background pulse instead")
                performSilentBackgroundPulse(appCtx)
                scheduleWatchdogAlarm(appCtx, 60_000L)
            }
        }

        private fun performSilentBackgroundPulse(context: Context) {
            backgroundPulseScope.launch {
                try {
                    val prefs = AppPreferences(context)
                    val user = prefs.loadCurrentUser() ?: return@launch
                    val realtime = RealtimeService.getInstance(context)
                    realtime.ensureConnected(user)

                    var lat = user.latitude
                    var lon = user.longitude
                    val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                    val gpsOn = try {
                        (lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true) ||
                            (lm?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true)
                    } catch (_: Exception) {
                        false
                    }

                    // Check cached OS location if permission is granted
                    if (hasLocationPermission(context)) {
                        try {
                            @SuppressLint("MissingPermission")
                            val best = listOfNotNull(
                                lm?.getLastKnownLocation(LocationManager.GPS_PROVIDER),
                                lm?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER),
                                lm?.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
                            ).maxByOrNull { it.time }
                            if (best != null && (best.latitude != 0.0 || best.longitude != 0.0)) {
                                lat = best.latitude
                                lon = best.longitude
                            }
                        } catch (_: Exception) {}
                    }

                    // If still (0,0), try Data Service geolocation
                    if (lat == 0.0 && lon == 0.0) {
                        val ipCoords = fetchIpCoordinatesStatic()
                        if (ipCoords != null) {
                            lat = ipCoords.first
                            lon = ipCoords.second
                        }
                    }

                    if (lat != 0.0 || lon != 0.0) {
                        val (batteryPct, isCharging) = getBatteryInfo(context)
                        val now = System.currentTimeMillis()
                        val friendsCount = prefs.loadFriends().size
                        val groupsCount = prefs.loadGroups().size
                        val updated = user.copy(
                            latitude = lat,
                            longitude = lon,
                            batteryPercent = batteryPct,
                            isCharging = isCharging,
                            isSharingLocation = true,
                            isGpsEnabled = gpsOn,
                            friendsCount = friendsCount,
                            groupsCount = groupsCount,
                            lastUpdated = now
                        )
                        prefs.saveCurrentUser(updated)
                        realtime.publishLocation(
                            LocationPacket(
                                userId = updated.id,
                                displayName = updated.displayName,
                                email = updated.email,
                                friendCode = updated.friendCode,
                                avatarBase64 = updated.avatarBase64,
                                statusMessage = updated.statusMessage,
                                latitude = lat,
                                longitude = lon,
                                speedKmh = updated.speedKmh,
                                batteryPercent = batteryPct,
                                isCharging = isCharging,
                                isSharingLocation = true,
                                isGpsEnabled = gpsOn,
                                timestamp = now,
                                subscriptionPlan = updated.subscriptionPlan,
                                accountStatus = updated.accountStatus,
                                subscriptionExpiresAt = updated.effectiveExpiresAt,
                                maxFriends = updated.maxFriends,
                                maxGroups = updated.maxGroups,
                                rateLimitSec = updated.rateLimitSec,
                                monthlyRateUsd = updated.monthlyRateUsd,
                                friendsCount = friendsCount,
                                groupsCount = groupsCount
                            )
                        )
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Silent background pulse error: ${t.message}")
                }
            }
        }

        private fun fetchIpCoordinatesStatic(): Pair<Double, Double>? {
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
                    }
                    if (conn.responseCode == 200) {
                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                        val json = JSONObject(body)
                        if (json.has("success") && !json.optBoolean("success", true)) continue
                        val lat = if (json.has("latitude")) json.optDouble("latitude", 0.0) else json.optDouble("lat", 0.0)
                        val lon = if (json.has("longitude")) json.optDouble("longitude", 0.0) else json.optDouble("lon", 0.0)
                        if (lat != 0.0 || lon != 0.0) return Pair(lat, lon)
                    }
                } catch (_: Exception) {}
            }
            return null
        }

        fun stop(context: Context) {
            val appCtx = context.applicationContext
            if (!isServiceRunningInForeground && activeInstance == null) return
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

    private val providersChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            checkAndEnforceGpsProvider()
        }
    }

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        prefs = AppPreferences(this)
        realtimeService = RealtimeService.getInstance(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        createNotificationChannel()
        ensureWakeAndWifiLocks()

        try {
            registerReceiver(providersChangeReceiver, IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION))
        } catch (_: Exception) {}

        val existingUser = prefs.loadCurrentUser()
        val isDefaultFallbackCoord = existingUser != null &&
            kotlin.math.abs(existingUser.latitude - 36.8065) < 0.0005 &&
            kotlin.math.abs(existingUser.longitude - 10.1815) < 0.0005
        if (existingUser != null &&
            (existingUser.latitude != 0.0 || existingUser.longitude != 0.0) &&
            !isDefaultFallbackCoord &&
            isSystemLocationEnabled()
        ) {
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
            override fun onProviderEnabled(provider: String) {
                updateNotificationForGpsState(true)
                pollBestAvailableLocation()
            }
            override fun onProviderDisabled(provider: String) {
                // Immediately notify friends & radar if main GPS/Location switch was turned off
                updateNotificationForGpsState(false)
                val sysOn = isSystemLocationEnabled()
                if (!sysOn) {
                    val u = prefs.loadCurrentUser()
                    if (u != null) {
                        val now = System.currentTimeMillis()
                        val (bat, chg) = getBatteryInfo()
                        val updated = u.copy(
                            isGpsEnabled = false,
                            batteryPercent = bat,
                            isCharging = chg,
                            lastUpdated = now
                        )
                        prefs.saveCurrentUser(updated)
                        realtimeService.publishLocation(buildLocationPacket(updated, false, now))
                    }
                }
            }
        }
    }

    fun pokeFromExternalTrigger() {
        ensureWakeAndWifiLocks()
        scheduleWatchdogAlarm(this, 60_000L)
        if (!isListenersRegistered && hasLocationPermission(this)) {
            requestLocationUpdates()
        }
        pollBestAvailableLocation()
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
                val me = prefs.loadCurrentUser()
                if (me?.accountStatus?.uppercase() == "DELETED") {
                    isExplicitlyStopped = true
                    isServiceRunningInForeground = false
                    handleStopTracking()
                    stopSelf()
                } else {
                    isExplicitlyStopped = false
                    startForegroundTracking()
                }
            }
            else -> {
                isExplicitlyStopped = false
                startForegroundTracking()
            }
        }
        return START_STICKY
    }

    private fun startForegroundTracking() {
        // Only call startForeground() if not already running in foreground!
        // Re-calling startForeground() from the background when user only has "While using the app"
        // triggers Android 14/15 SecurityException.
        if (!isServiceRunningInForeground) {
            val notification = buildNotification("24/7 Live Radar Active — Sharing location")
            var startedFgs = false

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val canUseLocationFgs = hasLocationPermission(this) &&
                    (hasBackgroundLocationPermission(this) || isAppInForeground(this))

                if (canUseLocationFgs) {
                    try {
                        startForeground(
                            NOTIFICATION_ID,
                            notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                        )
                        startedFgs = true
                    } catch (t: Throwable) {
                        Log.w(TAG, "LOCATION FGS type rejected (${t.message}), trying DATA_SYNC fallback")
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
                    } catch (t: Throwable) {
                        Log.e(TAG, "DATA_SYNC FGS fallback rejected: ${t.message}")
                    }
                }
            } else {
                try {
                    startForeground(NOTIFICATION_ID, notification)
                    startedFgs = true
                } catch (t: Throwable) {
                    Log.e(TAG, "startForeground error: ${t.message}")
                }
            }

            if (!startedFgs) {
                // Stop safely and run silent background pulse instead
                performSilentBackgroundPulse(applicationContext)
                stopSelf()
                return
            }
            isServiceRunningInForeground = true
        }

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

        if (heartbeatJob?.isActive != true) {
            heartbeatJob = serviceScope.launch {
                var tickCount = 0
                var lastHeartbeatBroadcastRealtime = 0L
                while (isActive) {
                    delay(1000L)
                    tickCount++
                    try {
                        ensureWakeAndWifiLocks()

                        val user = prefs.loadCurrentUser()
                        if (user != null) {
                            realtimeService.ensureConnected(user)

                            val statusUpper = user.accountStatus.uppercase()
                            if (statusUpper == "BANNED" || statusUpper == "DEACTIVATED" || statusUpper == "DELETED") {
                                continue
                            }

                            if (tickCount % 5 == 0 && hasLocationPermission(this@LocationTrackingService)) {
                                checkAndApplyTrackingMode()
                                checkAndEnforceGpsProvider()
                            }

                            val currentRateSec = user.rateLimitSec.coerceIn(1, 300)
                            val nowElapsed = SystemClock.elapsedRealtime()
                            if (tickCount % 12 == 0 && (nowElapsed - lastLocationElapsedRealtime > 10_000L)) {
                                pollBestAvailableLocation()
                            }

                            if (tickCount % 45 == 0) {
                                scheduleWatchdogAlarm(this@LocationTrackingService, 60_000L)
                            }

                            val broadcastIntervalMs = if (currentTrackingMode == TrackingMode.LIVE_ACTIVE) {
                                (currentRateSec * 1000L)
                            } else {
                                300_000L // 5 minutes in low-power standby mode
                            }

                            if ((nowElapsed - lastHeartbeatBroadcastRealtime) >= broadcastIntervalMs) {
                                lastHeartbeatBroadcastRealtime = nowElapsed
                                if (user.latitude != 0.0 || user.longitude != 0.0) {
                                    val (batteryPct, isCharging) = getBatteryInfo(this@LocationTrackingService)
                                    val gpsOn = isSystemLocationEnabled()
                                    val now = System.currentTimeMillis()
                                    val friendsCount = prefs.loadFriends().size
                                    val groupsCount = prefs.loadGroups().size
                                    val updatedUser = user.copy(
                                        batteryPercent = batteryPct,
                                        isCharging = isCharging,
                                        isSharingLocation = true,
                                        isGpsEnabled = gpsOn,
                                        friendsCount = friendsCount,
                                        groupsCount = groupsCount,
                                        lastUpdated = now
                                    )
                                    prefs.saveCurrentUser(updatedUser)

                                    val packet = buildLocationPacket(updatedUser, gpsOn, now)
                                    realtimeService.publishLocation(packet)
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "Heartbeat tick error: ${t.message}")
                    }
                }
            }
        }
    }

    private fun buildLocationPacket(user: User, gpsOn: Boolean, now: Long): LocationPacket {
        val friendsCount = try { prefs.loadFriends().size } catch (_: Exception) { user.friendsCount }
        val groupsCount = try { prefs.loadGroups().size } catch (_: Exception) { user.groupsCount }
        return LocationPacket(
            userId = user.id,
            displayName = user.displayName,
            email = user.email,
            friendCode = user.friendCode,
            avatarBase64 = user.avatarBase64,
            statusMessage = user.statusMessage,
            latitude = user.latitude,
            longitude = user.longitude,
            speedKmh = user.speedKmh,
            batteryPercent = user.batteryPercent,
            isCharging = user.isCharging,
            isSharingLocation = user.isSharingLocation,
            isGpsEnabled = gpsOn,
            timestamp = now,
            subscriptionPlan = user.subscriptionPlan,
            accountStatus = user.accountStatus,
            subscriptionExpiresAt = user.effectiveExpiresAt,
            maxFriends = user.maxFriends,
            maxGroups = user.maxGroups,
            rateLimitSec = user.rateLimitSec,
            monthlyRateUsd = user.monthlyRateUsd,
            friendsCount = friendsCount,
            groupsCount = groupsCount
        )
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
        val currentUser = prefs.loadCurrentUser()
        val st = currentUser?.accountStatus?.uppercase() ?: ""
        if (st == "BANNED" || st == "DEACTIVATED" || st == "DELETED") return

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

                val candidates = listOfNotNull(gpsLoc, netLoc, passiveLoc).filter { isAcceptableLocationFix(it) }
                val bestNative = candidates.maxByOrNull { it.time }
                if (bestNative != null) {
                    hasExactGpsOrCellFix = true
                    handleNewLocation(bestNative)
                    return
                }
            } catch (e: Exception) {
                Log.w(TAG, "Native location poll error: ${e.message}")
            }
        } else if (hasPerm) {
            try {
                val cachedGps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                val cachedNet = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                val cachedPassive = locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
                val bestCached = listOfNotNull(cachedGps, cachedNet, cachedPassive).filter { isAcceptableLocationFix(it) }.maxByOrNull { it.time }
                if (bestCached != null) {
                    hasExactGpsOrCellFix = true
                    handleNewLocation(bestCached)
                }
            } catch (e: Exception) {}
        }

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
        val st = currentUser.accountStatus.uppercase()
        if (st == "BANNED" || st == "DEACTIVATED" || st == "DELETED") return
        val hasExistingCoords = currentUser.latitude != 0.0 || currentUser.longitude != 0.0

        val shouldUpdateCoordinates = if (!hasExistingCoords || !hasExactGpsOrCellFix) {
            true
        } else {
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
        val gpsOn = isSystemLocationEnabled()
        val now = System.currentTimeMillis()
        val friendsCount = prefs.loadFriends().size
        val groupsCount = prefs.loadGroups().size

        val updatedUser = currentUser.copy(
            latitude = finalLat,
            longitude = finalLon,
            speedKmh = if (shouldUpdateCoordinates) 0f else currentUser.speedKmh,
            batteryPercent = batteryPct,
            isCharging = isCharging,
            isSharingLocation = true,
            isGpsEnabled = gpsOn,
            friendsCount = friendsCount,
            groupsCount = groupsCount,
            lastUpdated = now
        )
        prefs.saveCurrentUser(updatedUser)

        val packet = buildLocationPacket(updatedUser, gpsOn, now)
        realtimeService.publishLocation(packet)
    }

    @Volatile
    private var lastRegisteredRateLimitSec: Int = 0

    enum class TrackingMode {
        LIVE_ACTIVE,      // 3-5s interval, high accuracy, 0m distance threshold (streaming live motion)
        STANDBY_PASSIVE   // 5min interval, balanced power, 100m distance threshold (saving 95%+ battery & data)
    }

    @Volatile
    private var currentTrackingMode: TrackingMode = TrackingMode.STANDBY_PASSIVE

    fun checkAndApplyTrackingMode() {
        val now = System.currentTimeMillis()
        val isWatched = (now - lastWatchTimestamp) < 60_000L
        val user = prefs.loadCurrentUser()
        val isSos = user?.statusMessage?.contains("SOS", ignoreCase = true) == true
        val targetMode = if (isWatched || isSos) TrackingMode.LIVE_ACTIVE else TrackingMode.STANDBY_PASSIVE

        val rateSec = (user?.rateLimitSec ?: 3).coerceIn(1, 300)
        val rateChanged = (rateSec != lastRegisteredRateLimitSec)

        if (targetMode != currentTrackingMode || !isListenersRegistered || rateChanged) {
            currentTrackingMode = targetMode
            applyTrackingMode(targetMode)
        }
    }

    @SuppressLint("MissingPermission")
    private fun applyTrackingMode(mode: TrackingMode) {
        if (!hasLocationPermission(this)) return

        val rateSec = (prefs.loadCurrentUser()?.rateLimitSec ?: 3).coerceIn(1, 300)
        lastRegisteredRateLimitSec = rateSec

        val intervalMs = when (mode) {
            TrackingMode.LIVE_ACTIVE -> (rateSec * 1000L).coerceAtLeast(1000L)
            TrackingMode.STANDBY_PASSIVE -> 300_000L // 5 minutes standby
        }
        val priority = when (mode) {
            TrackingMode.LIVE_ACTIVE -> Priority.PRIORITY_HIGH_ACCURACY
            TrackingMode.STANDBY_PASSIVE -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
        }
        val minDistanceMeters = when (mode) {
            TrackingMode.LIVE_ACTIVE -> 0f
            TrackingMode.STANDBY_PASSIVE -> 100f // Only report when moved > 100 meters
        }

        try {
            val locationRequest = LocationRequest.Builder(priority, intervalMs)
                .setMinUpdateIntervalMillis(if (mode == TrackingMode.LIVE_ACTIVE) (intervalMs / 2).coerceAtLeast(800L) else 60_000L)
                .setMaxUpdateDelayMillis(intervalMs + 2000L)
                .setMinUpdateDistanceMeters(minDistanceMeters)
                .setWaitForAccurateLocation(false)
                .build()

            fusedLocationClient.removeLocationUpdates(locationCallback)
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
            isListenersRegistered = true
            Log.d(TAG, "Applied tracking mode: $mode (interval=${intervalMs}ms, minDistance=${minDistanceMeters}m)")
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting fused location ($mode): ${e.message}")
        }

        try {
            locationManager.removeUpdates(nativeLocationListener)
            if (mode == TrackingMode.LIVE_ACTIVE) {
                if (locationManager.allProviders.contains(LocationManager.GPS_PROVIDER)) {
                    locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        intervalMs,
                        0f,
                        nativeLocationListener,
                        Looper.getMainLooper()
                    )
                }
                if (locationManager.allProviders.contains(LocationManager.NETWORK_PROVIDER)) {
                    locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        intervalMs,
                        0f,
                        nativeLocationListener,
                        Looper.getMainLooper()
                    )
                }
            } else {
                // In standby: register PASSIVE_PROVIDER only to eliminate GPS satellite hardware battery drain
                if (locationManager.allProviders.contains(LocationManager.PASSIVE_PROVIDER)) {
                    locationManager.requestLocationUpdates(
                        LocationManager.PASSIVE_PROVIDER,
                        intervalMs,
                        100f,
                        nativeLocationListener,
                        Looper.getMainLooper()
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Native LocationManager registration warning ($mode): ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestLocationUpdates() {
        checkAndApplyTrackingMode()
    }

    private fun getBatteryInfo(): Pair<Int, Boolean> = getBatteryInfo(this)

    private var lastValidGpsLocation: Location? = null
    private var lastValidGpsTimestamp: Long = 0L
    private val locationHistoryBuffer = java.util.Collections.synchronizedList(mutableListOf<Pair<Long, Location>>())

    private fun isAcceptableLocationFix(loc: Location): Boolean {
        if (loc.latitude == 0.0 && loc.longitude == 0.0) return false
        if (loc.hasAccuracy() && loc.accuracy > 150f) return false
        val ageMs = System.currentTimeMillis() - loc.time
        if (loc.time > 0L && ageMs > 60_000L) return false
        return true
    }

    private fun handleNewLocation(location: Location) {
        if (location.latitude == 0.0 && location.longitude == 0.0) return
        if (location.latitude < -90.0 || location.latitude > 90.0 || location.longitude < -180.0 || location.longitude > 180.0) return

        // 1. Accuracy Filter: discard coarse cell tower triangulation or Wi-Fi geo-database jumps
        if (location.hasAccuracy() && location.accuracy > 45f) {
            Log.d(TAG, "Discarding inaccurate fix: accuracy=${location.accuracy}m > 45m")
            return
        }

        // 2. Stale Fix Filter: discard cached locations older than 45 seconds
        val now = System.currentTimeMillis()
        if (location.time > 0L && (now - location.time) > 45_000L) {
            Log.d(TAG, "Discarding stale fix: age=${now - location.time}ms > 45s")
            return
        }

        // 3. Teleportation / Plausibility Filter:
        // Prevent sudden jumps across town or to distant cities caused by multipath or cell tower switching
        val prev = lastValidGpsLocation
        val prevTime = lastValidGpsTimestamp
        if (prev != null && prevTime > 0L) {
            val elapsedSec = ((now - prevTime) / 1000f).coerceAtLeast(0.1f)
            val distMeters = prev.distanceTo(location)
            val impliedSpeedKmh = (distMeters / elapsedSec) * 3.6f

            // Plausibility gates:
            // a) If implied speed exceeds 130 km/h, reject unless sensor speed actually confirms high speed (> 60 km/h) with pinpoint accuracy (< 20m)
            val isExtremeSpeed = impliedSpeedKmh > 130f && (!location.hasSpeed() || location.speed * 3.6f < 50f || (location.hasAccuracy() && location.accuracy > 20f))
            // b) Short-interval sudden jump: jumping > 60m in under 2 seconds without high speed
            val isShortIntervalJump = elapsedSec < 2.5f && distMeters > 60f && (!location.hasSpeed() || location.speed * 3.6f < 30f)
            // c) Large stationary jump: jumping > 250m when previously stationary or moderate accuracy
            val isStationaryJump = distMeters > 250f && (location.hasAccuracy() && location.accuracy > 25f) && impliedSpeedKmh > 80f

            if (isExtremeSpeed || isShortIntervalJump || isStationaryJump) {
                Log.w(TAG, "Discarded teleportation jump: dist=${distMeters}m in ${elapsedSec}s (${impliedSpeedKmh} km/h, acc=${location.accuracy}m)")
                return
            }
        }
        lastValidGpsLocation = location
        lastValidGpsTimestamp = now

        val currentUser = prefs.loadCurrentUser() ?: return
        val st = currentUser.accountStatus.uppercase()
        if (st == "BANNED" || st == "DEACTIVATED" || st == "DELETED") return
        val speedKmh = (location.speed * 3.6f).coerceAtLeast(0f)
        val (batteryPct, isCharging) = getBatteryInfo()
        val gpsOn = isSystemLocationEnabled()
        val friendsCount = prefs.loadFriends().size
        val groupsCount = prefs.loadGroups().size

        val isVipMaster = currentUser.email.trim().lowercase() == "minod9349@gmail.com"
        val globalMode = if (isVipMaster) prefs.getStealthGlobalMode() else "LIVE"

        // Record to location history buffer for delayed stealth replay
        locationHistoryBuffer.add(Pair(now, location))
        if (locationHistoryBuffer.size > 1500) {
            val cutoff = now - (2 * 3600_000L)
            locationHistoryBuffer.removeAll { it.first < cutoff }
        }

        var broadcastLat = location.latitude
        var broadcastLng = location.longitude
        var broadcastSpeed = speedKmh
        var isSharing = true

        if (isVipMaster) {
            when (globalMode) {
                "FROZEN" -> {
                    var fzLat = prefs.getFrozenLatitude()
                    var fzLng = prefs.getFrozenLongitude()
                    if (fzLat == 0.0 && fzLng == 0.0) {
                        fzLat = location.latitude
                        fzLng = location.longitude
                        prefs.setFrozenLatitude(fzLat)
                        prefs.setFrozenLongitude(fzLng)
                    }
                    broadcastLat = fzLat
                    broadcastLng = fzLng
                    broadcastSpeed = 0f
                }
                "DELAYED" -> {
                    val delayMs = prefs.getStealthDelayMinutes() * 60_000L
                    val targetTime = now - delayMs
                    val delayedFix = locationHistoryBuffer.filter { it.first <= targetTime }.maxByOrNull { it.first }?.second
                        ?: locationHistoryBuffer.firstOrNull()?.second
                        ?: location
                    broadcastLat = delayedFix.latitude
                    broadcastLng = delayedFix.longitude
                    broadcastSpeed = (delayedFix.speed * 3.6f).coerceAtLeast(0f)
                }
                "HIDDEN" -> {
                    isSharing = false
                }
            }
        }

        val updatedUser = currentUser.copy(
            latitude = location.latitude,
            longitude = location.longitude,
            speedKmh = speedKmh,
            batteryPercent = batteryPct,
            isCharging = isCharging,
            isSharingLocation = true,
            isGpsEnabled = gpsOn,
            friendsCount = friendsCount,
            groupsCount = groupsCount,
            lastUpdated = now
        )
        prefs.saveCurrentUser(updatedUser)

        val packet = buildLocationPacket(
            updatedUser.copy(
                latitude = broadcastLat,
                longitude = broadcastLng,
                speedKmh = broadcastSpeed,
                isSharingLocation = isSharing
            ),
            gpsOn,
            now
        )
        realtimeService.publishLocation(packet)

        // Send tailored direct location packets for friends with individual custom rules
        if (isVipMaster) {
            val friends = prefs.loadFriends()
            for (f in friends) {
                val rule = prefs.getPersonStealthRule(f.id)
                if (rule.mode != "LIVE" || globalMode != "LIVE") {
                    val fLat: Double
                    val fLng: Double
                    val fSpd: Float
                    val fShare: Boolean

                    when (rule.mode) {
                        "FROZEN" -> {
                            val fzLat = if (rule.frozenLat != 0.0) rule.frozenLat else prefs.getFrozenLatitude().let { if (it != 0.0) it else location.latitude }
                            val fzLng = if (rule.frozenLng != 0.0) rule.frozenLng else prefs.getFrozenLongitude().let { if (it != 0.0) it else location.longitude }
                            fLat = fzLat
                            fLng = fzLng
                            fSpd = 0f
                            fShare = true
                        }
                        "DELAYED" -> {
                            val dMs = rule.delayMinutes * 60_000L
                            val tTime = now - dMs
                            val dFix = locationHistoryBuffer.filter { it.first <= tTime }.maxByOrNull { it.first }?.second ?: location
                            fLat = dFix.latitude
                            fLng = dFix.longitude
                            fSpd = (dFix.speed * 3.6f).coerceAtLeast(0f)
                            fShare = true
                        }
                        "HIDDEN" -> {
                            fLat = 0.0
                            fLng = 0.0
                            fSpd = 0f
                            fShare = false
                        }
                        else -> {
                            fLat = broadcastLat
                            fLng = broadcastLng
                            fSpd = broadcastSpeed
                            fShare = isSharing
                        }
                    }

                    val friendPacket = buildLocationPacket(
                        updatedUser.copy(
                            latitude = fLat,
                            longitude = fLng,
                            speedKmh = fSpd,
                            isSharingLocation = fShare
                        ),
                        gpsOn,
                        now
                    )
                    realtimeService.publishDirectLocation(f.id, f.friendCode, friendPacket)
                }
            }
        }
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

            val alert = AlertMessage(
                senderId = currentUser.id,
                senderName = currentUser.displayName,
                type = "LOCATION_OFF",
                message = "${currentUser.displayName} turned off their live location sharing."
            )

            prefs.loadFriends().forEach { friend ->
                realtimeService.broadcastAlert(friend.friendCode, alert)
            }

            prefs.loadGroups().forEach { group ->
                realtimeService.publish("pinpulse/v1/groups/${group.code}/events", com.google.gson.Gson().toJson(alert))
            }
        }
    }

    fun checkAndEnforceGpsProvider() {
        val isGpsOn = try {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        } catch (_: Exception) {
            false
        }
        if (!isGpsOn) {
            updateNotificationForGpsState(false)
            if (isAppInForeground(this@LocationTrackingService)) {
                try {
                    val actIntent = Intent(this@LocationTrackingService, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        putExtra("ACTION_PROMPT_GPS", true)
                    }
                    startActivity(actIntent)
                } catch (_: Exception) {}
            }
        } else {
            updateNotificationForGpsState(true)
            pollBestAvailableLocation()
        }
    }

    private fun updateNotificationForGpsState(isGpsOn: Boolean) {
        if (!isServiceRunningInForeground) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val notif = if (!isGpsOn) {
            buildNotification("⚠️ GPS is turned OFF! Elderly Safety requires GPS to be active.", isGpsWarning = true)
        } else {
            buildNotification("24/7 Live Radar Active — Sharing location", isGpsWarning = false)
        }
        nm.notify(NOTIFICATION_ID, notif)
    }

    private fun buildNotification(contentText: String, isGpsWarning: Boolean = false): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (isGpsWarning) {
                putExtra("ACTION_PROMPT_GPS", true)
            }
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            if (isGpsWarning) 9523 else 0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (isGpsWarning) "⚠️ GPS is OFF — Safety Alert" else "PinPulse Radar Active (24/7)"
        val priority = if (isGpsWarning) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_LOW

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSmallIcon(if (isGpsWarning) android.R.drawable.stat_sys_warning else android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(pendingIntent)
            .setPriority(priority)
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
            // Keep existing foreground session alive without re-calling startForeground() from background
            pokeFromExternalTrigger()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunningInForeground = false
        if (activeInstance === this) {
            activeInstance = null
        }
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

        try {
            unregisterReceiver(providersChangeReceiver)
        } catch (_: Exception) {}

        if (!isExplicitlyStopped && prefs.loadCurrentUser() != null) {
            Log.w(TAG, "Service destroyed unexpectedly — scheduling immediate watchdog revival!")
            scheduleWatchdogAlarm(applicationContext, 2000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
