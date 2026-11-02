package com.pinpulse.radar.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pinpulse.radar.MainActivity

class StopSirenReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            action = "SHOW_SIREN_PIN"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        context.startActivity(launchIntent)
    }
}

