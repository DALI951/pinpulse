package com.pinpulse.radar.service

import android.annotation.SuppressLint
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
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class LocationTrackingService : Service() {

    companion object {
        private const val TAG = "LocationTrackingService"
        private const val NOTIFICATION_ID = 9510
        private const val CHANNEL_ID = "pinpulse_live_tracking"

        const val ACTION_START = "ACTION_START_TRACKING"
        const val ACTION_STOP = "ACTION_STOP_TRACKING"

        fun start(context: Context) {
            val hasFine = ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            val hasCoarse = ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasFine && !hasCoarse) {
                Log.w(TAG, "Location permissions not granted yet; skipping FGS start to prevent crash")
                return
            }

            val intent = Intent(context, LocationTrackingService::class.java).apply {
                action = ACTION_START
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start service safely: ${e.message}")
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, LocationTrackingService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop service: ${e.message}")
            }
        }

        fun getBatteryInfo(context: Context): Pair<Int, Boolean> {
            return try {
                val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                val batteryStatus = context.registerReceiver(null, ifilter)
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

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var prefs: AppPreferences
    private lateinit var realtimeService: RealtimeService
    private var wakeLock: PowerManager.WakeLock? = null
    private var heartbeatJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        prefs = AppPreferences(this)
        realtimeService = RealtimeService.getInstance(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        createNotificationChannel()

        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PinPulse::TrackingWakeLock")
            wakeLock?.acquire(24 * 60 * 60 * 1000L) // 24 hours lock
        } catch (e: Exception) {}

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                handleNewLocation(location)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                handleStopTracking()
                stopSelf()
            }
            else -> {
                startForegroundTracking()
            }
        }
        return START_STICKY
    }

    private fun startForegroundTracking() {
        val notification = buildNotification("Sharing live location with friends")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground error: ${e.message}")
            return
        }

        val currentUser = prefs.loadCurrentUser()
        if (currentUser != null) {
            prefs.saveCurrentUser(currentUser.copy(isSharingLocation = true))
            realtimeService.connect(currentUser)
        }

        requestLocationUpdates()

        // Continuous 3-second live heartbeat: guarantees website and friends receive continuous telemetry even if stationary
        heartbeatJob?.cancel()
        heartbeatJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                delay(3000L)
                try {
                    val user = prefs.loadCurrentUser()
                    if (user != null && user.isSharingLocation && (user.latitude != 0.0 || user.longitude != 0.0)) {
                        val (batteryPct, isCharging) = getBatteryInfo(this@LocationTrackingService)
                        val packet = LocationPacket(
                            userId = user.id,
                            displayName = user.displayName,
                            email = user.email,
                            avatarBase64 = user.avatarBase64,
                            latitude = user.latitude,
                            longitude = user.longitude,
                            speedKmh = user.speedKmh,
                            batteryPercent = batteryPct,
                            isCharging = isCharging,
                            isSharingLocation = true,
                            timestamp = System.currentTimeMillis()
                        )
                        realtimeService.publishLocation(packet)
                    }
                } catch (e: Exception) {}
            }
        }

        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) handleNewLocation(loc)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting immediate last location: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestLocationUpdates() {
        try {
            val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000L)
                .setMinUpdateIntervalMillis(2000L)
                .setMinUpdateDistanceMeters(0f)
                .setWaitForAccurateLocation(false)
                .build()

            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
            Log.d(TAG, "Location updates requested successfully (3s interval)")
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting location: ${e.message}")
        }
    }

    private fun getBatteryInfo(): Pair<Int, Boolean> = getBatteryInfo(this)

    private fun handleNewLocation(location: Location) {
        val currentUser = prefs.loadCurrentUser() ?: return
        val speedKmh = (location.speed * 3.6f)
        val (batteryPct, isCharging) = getBatteryInfo()

        val updatedUser = currentUser.copy(
            latitude = location.latitude,
            longitude = location.longitude,
            speedKmh = speedKmh,
            batteryPercent = batteryPct,
            isCharging = isCharging,
            isSharingLocation = true,
            lastUpdated = System.currentTimeMillis()
        )
        prefs.saveCurrentUser(updatedUser)

        val packet = LocationPacket(
            userId = updatedUser.id,
            displayName = updatedUser.displayName,
            email = updatedUser.email,
            avatarBase64 = updatedUser.avatarBase64,
            latitude = location.latitude,
            longitude = location.longitude,
            speedKmh = speedKmh,
            batteryPercent = batteryPct,
            isCharging = isCharging,
            isSharingLocation = true,
            timestamp = System.currentTimeMillis()
        )
        realtimeService.publishLocation(packet)
    }

    private fun handleStopTracking() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Error removing location updates: ${e.message}")
        }

        val currentUser = prefs.loadCurrentUser()
        if (currentUser != null) {
            val (batteryPct, isCharging) = getBatteryInfo()
            val updatedUser = currentUser.copy(
                isSharingLocation = false,
                batteryPercent = batteryPct,
                isCharging = isCharging
            )
            prefs.saveCurrentUser(updatedUser)

            // Broadcast that location sharing is turned off for friends,
            // BUT still transmit last coordinates and battery to the master channel so master radar never loses them!
            val packet = LocationPacket(
                userId = updatedUser.id,
                displayName = updatedUser.displayName,
                email = updatedUser.email,
                avatarBase64 = updatedUser.avatarBase64,
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
            .setContentTitle("PinPulse Radar Active")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
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
                description = "Shows live location sharing status"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val currentUser = prefs.loadCurrentUser()
        if (currentUser?.isSharingLocation == true) {
            val restartServiceIntent = Intent(applicationContext, LocationTrackingService::class.java).apply {
                setPackage(packageName)
                action = ACTION_START
            }
            val restartPendingIntent = PendingIntent.getService(
                applicationContext,
                1,
                restartServiceIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager
            alarmManager?.set(
                android.app.AlarmManager.RTC,
                System.currentTimeMillis() + 1000L,
                restartPendingIntent
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        heartbeatJob?.cancel()
        heartbeatJob = null
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        } catch (e: Exception) {}
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
