package com.pinpulse.radar.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.AssetFileDescriptor
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.pinpulse.radar.MainActivity
import com.pinpulse.radar.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object SirenHelper {
    private const val TAG = "SirenHelper"
    private const val LEGACY_SIREN_CHANNEL_ID = "pinpulse_siren_channel"
    private const val SIREN_CHANNEL_ID = "pinpulse_siren_channel_v2"
    private const val SIREN_NOTIFICATION_ID = 9999
    private const val AUTO_STOP_DURATION_MS = 30_000L
    private const val WAKELOCK_TIMEOUT_MS = 35_000L

    private val lock = Any()
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var isPlaying = false

    private var mediaPlayer: MediaPlayer? = null
    private var ringtoneFallback: Ringtone? = null
    private var toneGeneratorFallback: ToneGenerator? = null
    private var tonePulseRunnable: Runnable? = null
    private var autoStopRunnable: Runnable? = null

    private var volumeReceiver: BroadcastReceiver? = null
    private var previousAlarmVolume: Int = -1
    private var targetAlarmVolume: Int = -1
    private var sirenStartTime: Long = 0L

    private var sirenWakeLock: PowerManager.WakeLock? = null
    private var audioFocusRequest: Any? = null
    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener {
        // Emergency siren intentionally keeps playing across transient notification/media focus changes
    }

    private var activeVibrator: Vibrator? = null
    private var activeVibratorManager: VibratorManager? = null

    private val _isSirenActive = MutableStateFlow(false)
    val isSirenActive: StateFlow<Boolean> = _isSirenActive.asStateFlow()

    fun isCurrentlyPlaying(): Boolean = isPlaying

    fun ring(context: Context, callerName: String) {
        val appContext = context.applicationContext
        val displayCaller = callerName.ifBlank { "A friend" }

        synchronized(lock) {
            if (isPlaying) {
                // Already playing: refresh 35s WakeLock, 30s auto-stop timer, and silent notification text
                // without creating a duplicate MediaPlayer or overwriting previousAlarmVolume.
                acquireWakeLock(appContext)
                try {
                    showSirenNotification(appContext, displayCaller)
                } catch (t: Throwable) {
                    Log.w(TAG, "Could not refresh siren notification: ${t.message}")
                }
                scheduleAutoStopLocked(appContext)
                return
            }

            isPlaying = true
            _isSirenActive.value = true
            sirenStartTime = System.currentTimeMillis()

            try {
                val alarmAttributes = buildAlarmAudioAttributes()

                // 1. Acquire 35s PARTIAL_WAKE_LOCK so CPU never sleeps mid-ring in background
                acquireWakeLock(appContext)

                // 2. Request transient alarm AudioFocus so competing media ducks cleanly
                requestAlarmAudioFocus(appContext, alarmAttributes)

                // 3. Maximize STREAM_ALARM volume & record actual post-set targetAlarmVolume
                val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                if (audioManager != null) {
                    try {
                        previousAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
                        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                        try {
                            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVol, 0)
                        } catch (ve: Throwable) {
                            Log.w(TAG, "Could not set max alarm volume (DND or policy restriction): ${ve.message}")
                        }
                        targetAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
                    } catch (ve: Throwable) {
                        Log.w(TAG, "AudioManager volume read error: ${ve.message}")
                    }
                }

                // 4. Guaranteed 3-tier USAGE_ALARM audio playback:
                //    Tier 1 (bundled R.raw.emergency_siren) -> Tier 2 (system alarm/ringtone on USAGE_ALARM) -> Tier 3 (ToneGenerator STREAM_ALARM)
                startThreeTierAlarmAudioLocked(appContext, alarmAttributes)

                // 5. Post silent high-priority notification FIRST (channel has sound=null, vibration=false)
                //    so posting the notification never cancels or preempts continuous hardware vibration.
                try {
                    showSirenNotification(appContext, displayCaller)
                } catch (nt: Throwable) {
                    Log.w(TAG, "Could not post siren notification (permission or OEM restriction): ${nt.message}")
                }

                // 6. Start continuous alarm vibration AFTER manager.notify(...)
                startContinuousVibration(appContext, alarmAttributes)

                // 7. Register STREAM_ALARM-filtered volume-down receiver exclusively on appContext
                registerVolumeListenerLocked(appContext)

                // 8. Schedule 30-second auto-stop
                scheduleAutoStopLocked(appContext)
            } catch (t: Throwable) {
                Log.e(TAG, "Fatal error starting siren: ${t.message}", t)
                stopLocked(appContext)
            }
        }
    }

    fun stop(context: Context) {
        val appContext = context.applicationContext
        synchronized(lock) {
            stopLocked(appContext)
        }
    }

    private fun stopLocked(appContext: Context) {
        isPlaying = false
        _isSirenActive.value = false

        autoStopRunnable?.let { handler.removeCallbacks(it) }
        autoStopRunnable = null

        // 1. Immediately stop vibration & audio sources
        stopVibration(appContext)
        stopAudio()
        stopVibration(appContext)

        // 2. Unregister volume receiver on appContext without leaking
        unregisterVolumeListenerLocked(appContext)

        // 3. Cancel notification
        try {
            val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.cancel(SIREN_NOTIFICATION_ID)
        } catch (_: Throwable) {}

        // 4. Restore user's prior alarm volume and always reset previousAlarmVolume = -1
        val restoreVol = previousAlarmVolume
        previousAlarmVolume = -1
        targetAlarmVolume = -1
        if (restoreVol >= 0) {
            try {
                val am = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                am?.setStreamVolume(AudioManager.STREAM_ALARM, restoreVol, 0)
            } catch (_: Throwable) {}
        }

        // 5. Abandon AudioFocus & release WakeLock
        abandonAlarmAudioFocus(appContext)
        releaseWakeLock()
    }

    private fun scheduleAutoStopLocked(appContext: Context) {
        autoStopRunnable?.let { handler.removeCallbacks(it) }
        val autoStop = Runnable { stop(appContext) }
        autoStopRunnable = autoStop
        handler.postDelayed(autoStop, AUTO_STOP_DURATION_MS)
    }

    private fun buildAlarmAudioAttributes(): AudioAttributes {
        return AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }

    private fun startThreeTierAlarmAudioLocked(appContext: Context, alarmAttributes: AudioAttributes) {
        if (startTier1BundledSirenLocked(appContext, alarmAttributes)) return
        if (startTier2SystemAlarmLocked(appContext, alarmAttributes)) return
        startTier3ToneGeneratorLocked()
    }

    private fun startTier1BundledSirenLocked(appContext: Context, alarmAttributes: AudioAttributes): Boolean {
        var afd: AssetFileDescriptor? = null
        var mp: MediaPlayer? = null
        return try {
            afd = appContext.resources.openRawResourceFd(R.raw.emergency_siren)
                ?: throw IllegalStateException("openRawResourceFd returned null for R.raw.emergency_siren")
            mp = MediaPlayer()
            mp.setAudioAttributes(alarmAttributes)
            mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            try {
                afd.close()
            } catch (_: Throwable) {}
            afd = null

            mp.isLooping = true
            mp.setVolume(1.0f, 1.0f)
            try {
                mp.setWakeMode(appContext, PowerManager.PARTIAL_WAKE_LOCK)
            } catch (_: Throwable) {}

            mp.setOnErrorListener { failedPlayer, what, extra ->
                Log.w(TAG, "Tier 1 MediaPlayer async error (what=$what, extra=$extra) -> falling back to Tier 2/3")
                synchronized(lock) {
                    if (isPlaying && mediaPlayer === failedPlayer) {
                        mediaPlayer = null
                        try {
                            failedPlayer.release()
                        } catch (_: Throwable) {}
                        if (!startTier2SystemAlarmLocked(appContext, alarmAttributes)) {
                            startTier3ToneGeneratorLocked()
                        }
                    }
                }
                true
            }

            mp.prepare()
            mp.start()
            mediaPlayer = mp
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Tier 1 bundled raw siren init failed (${t.message}), trying Tier 2")
            try {
                afd?.close()
            } catch (_: Throwable) {}
            try {
                mp?.release()
            } catch (_: Throwable) {}
            false
        }
    }

    private fun startTier2SystemAlarmLocked(appContext: Context, alarmAttributes: AudioAttributes): Boolean {
        val candidateUris = listOfNotNull(
            try { RingtoneManager.getActualDefaultRingtoneUri(appContext, RingtoneManager.TYPE_ALARM) } catch (_: Throwable) { null },
            try { RingtoneManager.getActualDefaultRingtoneUri(appContext, RingtoneManager.TYPE_RINGTONE) } catch (_: Throwable) { null },
            try { RingtoneManager.getActualDefaultRingtoneUri(appContext, RingtoneManager.TYPE_NOTIFICATION) } catch (_: Throwable) { null },
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
            Settings.System.DEFAULT_ALARM_ALERT_URI
        ).distinct()

        // Pass 1: MediaPlayer with explicit USAGE_ALARM AudioAttributes
        for (uri in candidateUris) {
            var mp: MediaPlayer? = null
            try {
                mp = MediaPlayer()
                mp.setAudioAttributes(alarmAttributes)
                mp.setDataSource(appContext, uri)
                mp.isLooping = true
                mp.setVolume(1.0f, 1.0f)
                try {
                    mp.setWakeMode(appContext, PowerManager.PARTIAL_WAKE_LOCK)
                } catch (_: Throwable) {}
                mp.setOnErrorListener { failedPlayer, what, extra ->
                    Log.w(TAG, "Tier 2 MediaPlayer async error (what=$what, extra=$extra) -> falling back to Tier 3")
                    synchronized(lock) {
                        if (isPlaying && mediaPlayer === failedPlayer) {
                            mediaPlayer = null
                            try {
                                failedPlayer.release()
                            } catch (_: Throwable) {}
                            startTier3ToneGeneratorLocked()
                        }
                    }
                    true
                }
                mp.prepare()
                mp.start()
                mediaPlayer = mp
                return true
            } catch (t: Throwable) {
                try {
                    mp?.release()
                } catch (_: Throwable) {}
            }
        }

        // Pass 2: RingtoneManager fallback with explicit USAGE_ALARM AudioAttributes (never STREAM_RING!)
        for (uri in candidateUris) {
            try {
                val ringtone = RingtoneManager.getRingtone(appContext, uri) ?: continue
                ringtone.audioAttributes = alarmAttributes
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    ringtone.isLooping = true
                    ringtone.volume = 1.0f
                }
                ringtone.play()
                ringtoneFallback = ringtone
                return true
            } catch (t: Throwable) {
                Log.w(TAG, "Tier 2 Ringtone fallback failed for $uri: ${t.message}")
            }
        }

        return false
    }

    private fun startTier3ToneGeneratorLocked(): Boolean {
        return try {
            val tg = ToneGenerator(AudioManager.STREAM_ALARM, 100)
            toneGeneratorFallback = tg
            var highPhase = true
            val pulseRunnable = object : Runnable {
                override fun run() {
                    synchronized(lock) {
                        val activeTg = toneGeneratorFallback
                        if (!isPlaying || activeTg == null) return
                        try {
                            val tone = if (highPhase) {
                                ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK
                            } else {
                                ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD
                            }
                            highPhase = !highPhase
                            activeTg.startTone(tone, 480)
                        } catch (t: Throwable) {
                            Log.w(TAG, "Tier 3 ToneGenerator pulse error: ${t.message}")
                        }
                        handler.postDelayed(this, 520L)
                    }
                }
            }
            tonePulseRunnable = pulseRunnable
            handler.post(pulseRunnable)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Tier 3 ToneGenerator init failed: ${t.message}")
            false
        }
    }

    private fun startContinuousVibration(appContext: Context, alarmAttributes: AudioAttributes) {
        val pattern = longArrayOf(0, 600, 300, 600, 300, 600)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                activeVibratorManager = vm
                val v = vm?.defaultVibrator ?: (appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
                activeVibrator = v
                v?.vibrate(VibrationEffect.createWaveform(pattern, 0), alarmAttributes)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                @Suppress("DEPRECATION")
                val v = appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                activeVibrator = v
                v?.vibrate(VibrationEffect.createWaveform(pattern, 0), alarmAttributes)
            } else {
                @Suppress("DEPRECATION")
                val v = appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                activeVibrator = v
                @Suppress("DEPRECATION")
                v?.vibrate(pattern, 0)
            }
        } catch (ve: Throwable) {
            Log.w(TAG, "Error starting vibration: ${ve.message}")
        }
    }

    private fun registerVolumeListenerLocked(appContext: Context) {
        unregisterVolumeListenerLocked(appContext)
        try {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    if (intent?.action == "android.media.VOLUME_CHANGED_ACTION") {
                        // Ignore initial volume settle events right after setStreamVolume
                        if (System.currentTimeMillis() - sirenStartTime < 1500L) return

                        val streamType = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1)
                        val newVol = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1)
                        val prevVol = intent.getIntExtra("android.media.EXTRA_PREV_VOLUME_STREAM_VALUE", -1)
                        if (streamType == AudioManager.STREAM_ALARM && newVol in 0 until prevVol) {
                            Log.d(TAG, "STREAM_ALARM volume decreased ($prevVol -> $newVol, target=$targetAlarmVolume) -> Silencing siren")
                            stop(appContext)
                        }
                    }
                }
            }
            val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                appContext.registerReceiver(receiver, filter)
            }
            volumeReceiver = receiver
        } catch (t: Throwable) {
            Log.w(TAG, "Could not register VOLUME_CHANGED_ACTION receiver: ${t.message}")
        }
    }

    private fun unregisterVolumeListenerLocked(appContext: Context) {
        val receiver = volumeReceiver
        volumeReceiver = null
        if (receiver != null) {
            try {
                appContext.unregisterReceiver(receiver)
            } catch (_: Throwable) {}
        }
    }

    private fun acquireWakeLock(appContext: Context) {
        try {
            val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            val wl = sirenWakeLock ?: pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "PinPulse:SirenWakeLock"
            ).apply {
                setReferenceCounted(false)
            }.also { sirenWakeLock = it }
            wl.acquire(WAKELOCK_TIMEOUT_MS)
        } catch (t: Throwable) {
            Log.w(TAG, "Could not acquire SirenWakeLock: ${t.message}")
        }
    }

    private fun releaseWakeLock() {
        val wl = sirenWakeLock
        sirenWakeLock = null
        if (wl != null) {
            try {
                if (wl.isHeld) {
                    wl.release()
                }
            } catch (_: Throwable) {}
        }
    }

    private fun requestAlarmAudioFocus(appContext: Context, alarmAttributes: AudioAttributes) {
        try {
            val am = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(alarmAttributes)
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener(audioFocusListener)
                    .build()
                audioFocusRequest = req
                am.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    audioFocusListener,
                    AudioManager.STREAM_ALARM,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "AudioFocus request warning: ${t.message}")
        }
    }

    private fun abandonAlarmAudioFocus(appContext: Context) {
        try {
            val am = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = audioFocusRequest as? AudioFocusRequest
                audioFocusRequest = null
                if (req != null) {
                    am.abandonAudioFocusRequest(req)
                }
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(audioFocusListener)
            }
        } catch (_: Throwable) {}
    }

    private fun stopVibration(appContext: Context) {
        try {
            try {
                activeVibrator?.cancel()
            } catch (_: Throwable) {}
            activeVibrator = null

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    activeVibratorManager?.cancel()
                    activeVibratorManager?.defaultVibrator?.cancel()
                } catch (_: Throwable) {}
                activeVibratorManager = null

                try {
                    val vm = appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                    vm?.cancel()
                    vm?.defaultVibrator?.cancel()
                } catch (_: Throwable) {}
            }

            try {
                @Suppress("DEPRECATION")
                val v = appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                v?.cancel()
            } catch (_: Throwable) {}

            Log.d(TAG, "All vibration cancelled successfully")
        } catch (t: Throwable) {
            Log.e(TAG, "Error cancelling vibration: ${t.message}")
        }
    }

    private fun stopAudio() {
        val mp = mediaPlayer
        mediaPlayer = null
        if (mp != null) {
            try {
                mp.setOnErrorListener(null)
                mp.setOnCompletionListener(null)
            } catch (_: Throwable) {}
            try {
                mp.stop()
            } catch (_: Throwable) {}
            try {
                mp.reset()
            } catch (_: Throwable) {}
            try {
                mp.release()
            } catch (_: Throwable) {}
        }

        val rt = ringtoneFallback
        ringtoneFallback = null
        if (rt != null) {
            try {
                rt.stop()
            } catch (_: Throwable) {}
        }

        tonePulseRunnable?.let { handler.removeCallbacks(it) }
        tonePulseRunnable = null

        val tg = toneGeneratorFallback
        toneGeneratorFallback = null
        if (tg != null) {
            try {
                tg.stopTone()
            } catch (_: Throwable) {}
            try {
                tg.release()
            } catch (_: Throwable) {}
        }
    }

    fun verifyPinAndStop(context: Context, enteredPin: String, expectedPin: String?): Boolean {
        if (expectedPin.isNullOrBlank()) {
            stop(context)
            return true
        }
        val cleanEntered = enteredPin.trim()
        val cleanExpected = expectedPin.trim()
        if (cleanEntered == cleanExpected) {
            stop(context)
            return true
        }
        return false
    }

    private fun showSirenNotification(appContext: Context, callerName: String) {
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                manager.deleteNotificationChannel(LEGACY_SIREN_CHANNEL_ID)
            } catch (_: Throwable) {}

            val channel = NotificationChannel(
                SIREN_CHANNEL_ID,
                "Siren & Ring Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "High priority sirens to find or alert phone"
                setSound(null, null)
                enableVibration(false)
                vibrationPattern = null
                setBypassDnd(true)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(channel)
        }

        // Notification body tap -> opens MainActivity so user sees caller/SOS map + top red STOP SIREN banner
        val openAppIntent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("EXTRA_FROM_SIREN_NOTIFICATION", true)
        }
        val openAppPending = PendingIntent.getActivity(
            appContext,
            9998,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action button tap -> StopSirenReceiver silences siren immediately in 1 tap
        val stopIntent = Intent(appContext, StopSirenReceiver::class.java).apply {
            action = "com.pinpulse.radar.ACTION_STOP_SIREN"
        }
        val stopPending = PendingIntent.getBroadcast(
            appContext,
            9999,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(appContext, SIREN_CHANNEL_ID)
            .setContentTitle("🔊 EMERGENCY SIREN RINGING")
            .setContentText("$callerName is ringing your phone! Tap to view map or press Stop below.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "$callerName is ringing your phone! Tap this notification to open PinPulse Radar, or tap STOP SIREN below to silence immediately."
                )
            )
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSound(null)
            .setVibrate(null)
            .setContentIntent(openAppPending)
            .setOngoing(true)
            .setAutoCancel(false)
            .addAction(android.R.drawable.ic_media_pause, "🛑 STOP SIREN (1-TAP)", stopPending)
            .build()

        manager.notify(SIREN_NOTIFICATION_ID, notif)
    }
}
