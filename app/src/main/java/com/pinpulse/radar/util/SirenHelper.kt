package com.pinpulse.radar.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object SirenHelper {
    private const val TAG = "SirenHelper"
    private const val SIREN_CHANNEL_ID = "pinpulse_siren_channel"
    private const val SIREN_NOTIFICATION_ID = 9999

    private var mediaPlayer: MediaPlayer? = null
    private var isPlaying = false
    private val handler = Handler(Looper.getMainLooper())
    private var autoStopRunnable: Runnable? = null

    private var volumeReceiver: BroadcastReceiver? = null
    private var volumeObserver: ContentObserver? = null
    private var previousAlarmVolume: Int = -1
    private var targetAlarmVolume: Int = -1
    private var sirenStartTime: Long = 0L
    private var ringtoneFallback: android.media.Ringtone? = null

    private var activeVibrator: Vibrator? = null
    private var activeVibratorManager: VibratorManager? = null

    private val _isSirenActive = MutableStateFlow(false)
    val isSirenActive: StateFlow<Boolean> = _isSirenActive.asStateFlow()

    fun isCurrentlyPlaying(): Boolean = isPlaying

    fun ring(context: Context, callerName: String) {
        if (isPlaying) return
        isPlaying = true
        _isSirenActive.value = true
        sirenStartTime = System.currentTimeMillis()

        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            previousAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
            targetAlarmVolume = maxVol
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVol, 0)

            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            try {
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(context, alarmUri)
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    isLooping = true
                    prepare()
                    start()
                }
            } catch (e: Exception) {
                Log.w(TAG, "MediaPlayer init error: ${e.message}, attempting RingtoneManager fallback")
                val ringtone = RingtoneManager.getRingtone(context, alarmUri)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    ringtone?.isLooping = true
                }
                ringtone?.play()
                ringtoneFallback = ringtone
            }

            // Vibrate pattern
            val pattern = longArrayOf(0, 600, 300, 600, 300, 600)
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val appContext = context.applicationContext
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vm = appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                    activeVibratorManager = vm
                    val v = vm?.defaultVibrator ?: (appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
                    activeVibrator = v
                    v?.vibrate(VibrationEffect.createWaveform(pattern, 0), audioAttributes)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    @Suppress("DEPRECATION")
                    val v = appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                    activeVibrator = v
                    v?.vibrate(VibrationEffect.createWaveform(pattern, 0), audioAttributes)
                } else {
                    @Suppress("DEPRECATION")
                    val v = appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                    activeVibrator = v
                    v?.vibrate(pattern, 0)
                }
            } catch (ve: Exception) {
                Log.w(TAG, "Error starting vibration: ${ve.message}")
            }

            // Show high-priority Heads-up Notification with "Stop Siren" button
            showSirenNotification(context, callerName)

            // Register hardware volume change receiver to catch Volume Down key presses in background
            registerVolumeListeners(context)

            // Auto-stop after 30 seconds
            autoStopRunnable?.let { handler.removeCallbacks(it) }
            val autoStop = Runnable { stop(context) }
            autoStopRunnable = autoStop
            handler.postDelayed(autoStop, 30_000L)

        } catch (e: Exception) {
            Log.e(TAG, "Error playing siren: ${e.message}")
            stop(context)
        }
    }

    private fun registerVolumeListeners(context: Context) {
        try {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    if (intent?.action == "android.media.VOLUME_CHANGED_ACTION") {
                        // Ignore broadcast for first 2.5 seconds while initial max volume level settles
                        if (System.currentTimeMillis() - sirenStartTime < 2500L) return

                        val newVol = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1)
                        val prevVol = intent.getIntExtra("android.media.EXTRA_PREV_VOLUME_STREAM_VALUE", -1)
                        if (newVol in 0 until prevVol) {
                            Log.d(TAG, "Hardware volume down detected ($prevVol -> $newVol) -> Silencing siren and vibration")
                            stop(context)
                        }
                    }
                }
            }
            volumeReceiver = receiver
            val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not register VOLUME_CHANGED_ACTION receiver: ${e.message}")
        }

        try {
            val observer = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) {
                    // Ignore observer events for first 2.5 seconds while initial volume maxes out
                    if (System.currentTimeMillis() - sirenStartTime < 2500L) return

                    val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
                    val currentVol = am.getStreamVolume(AudioManager.STREAM_ALARM)
                    if (currentVol < targetAlarmVolume) {
                        Log.d(TAG, "Alarm volume decreased ($currentVol < $targetAlarmVolume) -> Silencing siren and vibration")
                        stop(context)
                    }
                }
            }
            volumeObserver = observer
            context.contentResolver.registerContentObserver(
                Settings.System.CONTENT_URI,
                true,
                observer
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not register volume ContentObserver: ${e.message}")
        }
    }

    fun stop(context: Context) {
        // 1. Immediately kill vibration unconditionally on all subsystems
        stopVibration(context)

        // 2. Immediately stop all audio sources
        stopAudio()

        if (!isPlaying) return
        isPlaying = false
        _isSirenActive.value = false
        autoStopRunnable?.let { handler.removeCallbacks(it) }

        // Cancel notification
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(SIREN_NOTIFICATION_ID)
        } catch (e: Exception) {}

        // Unregister volume listeners
        try {
            volumeReceiver?.let { context.unregisterReceiver(it) }
            volumeReceiver = null
        } catch (e: Exception) {}

        try {
            volumeObserver?.let { context.contentResolver.unregisterContentObserver(it) }
            volumeObserver = null
        } catch (e: Exception) {}

        // Restore user's prior alarm volume
        try {
            if (previousAlarmVolume >= 0) {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                am?.setStreamVolume(AudioManager.STREAM_ALARM, previousAlarmVolume, 0)
            }
        } catch (e: Exception) {}
    }

    private fun stopVibration(context: Context) {
        try {
            val appContext = context.applicationContext

            // 1. Cancel on active cached references
            try {
                activeVibrator?.cancel()
            } catch (e: Exception) {}
            activeVibrator = null

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    activeVibratorManager?.cancel()
                    activeVibratorManager?.defaultVibrator?.cancel()
                } catch (e: Exception) {}
                activeVibratorManager = null
            }

            // 2. Fresh cancellation via VibratorManager system service (API 31+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    val vm = appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                    vm?.cancel()
                    vm?.defaultVibrator?.cancel()
                } catch (e: Exception) {}
            }

            // 3. Fresh cancellation via Vibrator system service (all Android versions)
            try {
                @Suppress("DEPRECATION")
                val v = appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                v?.cancel()
            } catch (e: Exception) {}

            // 4. Also check direct context
            try {
                @Suppress("DEPRECATION")
                val directV = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                directV?.cancel()
            } catch (e: Exception) {}

            Log.d(TAG, "All vibration cancelled successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling vibration: ${e.message}")
        }
    }

    private fun stopAudio() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (e: Exception) {}

        try {
            ringtoneFallback?.stop()
            ringtoneFallback = null
        } catch (e: Exception) {}
    }

    private fun showSirenNotification(context: Context, callerName: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                SIREN_CHANNEL_ID,
                "Siren & Ring Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "High priority sirens to find or alert phone"
                enableVibration(true)
            }
            manager.createNotificationChannel(channel)
        }

        val stopIntent = Intent(context, StopSirenReceiver::class.java)
        val stopPending = PendingIntent.getBroadcast(
            context,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(context, SIREN_CHANNEL_ID)
            .setContentTitle("🔊 EMERGENCY SIREN RINGING")
            .setContentText("$callerName is ringing your phone! Press Volume Down or tap Stop.")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "🛑 STOP SIREN", stopPending)
            .build()

        manager.notify(SIREN_NOTIFICATION_ID, notif)
    }
}
