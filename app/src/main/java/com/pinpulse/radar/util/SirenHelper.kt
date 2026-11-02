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
    private var initialAlarmVolume: Int = -1

    private val _isSirenActive = MutableStateFlow(false)
    val isSirenActive: StateFlow<Boolean> = _isSirenActive.asStateFlow()

    fun isCurrentlyPlaying(): Boolean = isPlaying

    fun ring(context: Context, callerName: String) {
        if (isPlaying) return
        isPlaying = true
        _isSirenActive.value = true

        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            initialAlarmVolume = maxVol
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVol, 0)

            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

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

            // Vibrate pattern
            val pattern = longArrayOf(0, 600, 300, 600, 300, 600)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                @Suppress("DEPRECATION")
                val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                v?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                v?.vibrate(pattern, 0)
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
                        Log.d(TAG, "Hardware volume change detected -> Silencing siren and vibration")
                        stop(context)
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
                    val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
                    val currentVol = am.getStreamVolume(AudioManager.STREAM_ALARM)
                    if (currentVol < initialAlarmVolume || currentVol == 0) {
                        Log.d(TAG, "Volume dropped via hardware key -> Silencing siren and vibration")
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
        if (!isPlaying) return
        isPlaying = false
        _isSirenActive.value = false
        autoStopRunnable?.let { handler.removeCallbacks(it) }

        // Unregister volume listeners
        try {
            volumeReceiver?.let { context.unregisterReceiver(it) }
            volumeReceiver = null
        } catch (e: Exception) {}

        try {
            volumeObserver?.let { context.contentResolver.unregisterContentObserver(it) }
            volumeObserver = null
        } catch (e: Exception) {}

        // Stop audio playback immediately
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (e: Exception) {}

        // Stop vibration immediately on all Android versions
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.cancel()
                vm?.defaultVibrator?.cancel()
            }
            @Suppress("DEPRECATION")
            val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            v?.cancel()
        } catch (e: Exception) {}

        // Cancel notification
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(SIREN_NOTIFICATION_ID)
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
