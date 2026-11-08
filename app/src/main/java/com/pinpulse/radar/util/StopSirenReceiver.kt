package com.pinpulse.radar.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class StopSirenReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Emergency SOS siren must be stoppable instantly with NO PIN
        SirenHelper.stop(context)
    }
}

