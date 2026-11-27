package com.pinpulse.radar.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class StopSirenReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("StopSirenReceiver", "1-Tap Stop Siren action received")
        // Emergency SOS / Find-Phone siren must be stoppable instantly with NO PIN
        SirenHelper.stop(context.applicationContext)
    }
}
