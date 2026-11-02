package com.pinpulse.radar.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pinpulse.radar.data.AppPreferences

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            val prefs = AppPreferences(context)
            val currentUser = prefs.loadCurrentUser()
            if (currentUser != null && currentUser.isSharingLocation) {
                LocationTrackingService.start(context)
            }
        }
    }
}
