package com.pinpulse.radar.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
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

class LocationTrackingService : Service() {

    companion object {
        private const val TAG = "LocationTrackingService"
        private const val NOTIFICATION_ID = 9510
        private const val CHANNEL_ID = "pinpulse_live_tracking"

        const val ACTION_START = "ACTION_START_TRACKING"
        const val ACTION_STOP = "ACTION_STOP_TRACKING"

        fun start(context: Context) {
            val intent = Intent(context, LocationTrackingService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, LocationTrackingService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var prefs: AppPreferences
    private lateinit var realtimeService: RealtimeService
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        prefs = AppPreferences(this)
        realtimeService = RealtimeService.getInstance(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        createNotificationChannel()

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PinPulse::TrackingWakeLock")
        wakeLock?.acquire(24 * 60 * 60 * 1000L) // 24 hours lock

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
        val notification = buildNotification("Sharing live location with your friends")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val currentUser = prefs.loadCurrentUser()
        if (currentUser != null) {
            prefs.saveCurrentUser(currentUser.copy(isSharingLocation = true))
            realtimeService.connect(currentUser.id)
        }

        requestLocationUpdates()
    }

    @SuppressLint("MissingPermission")
    private fun requestLocationUpdates() {
        try {
            val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000L)
                .setMinUpdateIntervalMillis(2000L)
                .setMinUpdateDistanceMeters(1.5f)
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

    private fun handleNewLocation(location: Location) {
        val currentUser = prefs.loadCurrentUser() ?: return
        val speedKmh = (location.speed * 3.6f)

        val updatedUser = currentUser.copy(
            latitude = location.latitude,
            longitude = location.longitude,
            speedKmh = speedKmh,
            isSharingLocation = true,
            lastUpdated = System.currentTimeMillis()
        )
        prefs.saveCurrentUser(updatedUser)

        val packet = LocationPacket(
            userId = updatedUser.id,
            displayName = updatedUser.displayName,
            avatarBase64 = updatedUser.avatarBase64,
            latitude = location.latitude,
            longitude = location.longitude,
            speedKmh = speedKmh,
            isSharingLocation = true,
            timestamp = System.currentTimeMillis()
        )
        realtimeService.publishLocation(packet)
    }

    private fun handleStopTracking() {
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Error removing location updates: ${e.message}")
        }

        val currentUser = prefs.loadCurrentUser()
        if (currentUser != null) {
            val updatedUser = currentUser.copy(isSharingLocation = false)
            prefs.saveCurrentUser(updatedUser)

            // Broadcast that location is turned off
            val packet = LocationPacket(
                userId = updatedUser.id,
                displayName = updatedUser.displayName,
                avatarBase64 = updatedUser.avatarBase64,
                latitude = updatedUser.latitude,
                longitude = updatedUser.longitude,
                speedKmh = 0f,
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
                realtimeService.broadcastAlert(friend.id, alert)
            }

            // Alert all group channels
            prefs.loadGroups().forEach { group ->
                group.memberIds.filterNot { it == currentUser.id }.forEach { memberId ->
                    realtimeService.broadcastAlert(memberId, alert)
                }
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

        val stopIntent = Intent(this, LocationTrackingService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PinPulse Radar Active")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Pause Sharing", stopPendingIntent)
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

    override fun onDestroy() {
        super.onDestroy()
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Error on destroy: ${e.message}")
        }
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
