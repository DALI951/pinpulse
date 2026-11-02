package com.pinpulse.radar.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class StopSirenReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        SirenHelper.stop(context)
    }
}
