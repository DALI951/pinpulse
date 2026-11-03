package com.pinpulse.radar

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log

class PinPulseApp : Application() {

    override fun onCreate() {
        super.onCreate()
        installCrashShield()
    }

    private fun installCrashShield() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("PinPulseCrashShield", "Caught unexpected exception on thread ${thread.name}: ${throwable.message}", throwable)

            if (thread.id == Looper.getMainLooper().thread.id) {
                // Keep the main Looper alive if a non-fatal UI/service callback exception occurred
                while (true) {
                    try {
                        Looper.loop()
                    } catch (t: Throwable) {
                        Log.e("PinPulseCrashShield", "Recovered main looper exception: ${t.message}", t)
                        // If it's a fatal OutOfMemoryError, delegate to default handler
                        if (t is OutOfMemoryError) {
                            defaultHandler?.uncaughtException(thread, t)
                            break
                        }
                    }
                }
            }
            // For background worker threads (MQTT, network, tile loading), swallow and log
            // so Android/OneUI never kills the app process or shows "Something went wrong with PinPulse"
        }
    }
}
