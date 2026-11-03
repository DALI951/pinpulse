package com.pinpulse.radar.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.gson.Gson
import com.pinpulse.radar.data.AppPreferences
import com.pinpulse.radar.data.RealtimeService
import com.pinpulse.radar.model.AlertMessage

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val prefs = AppPreferences(context)
        val currentUser = prefs.loadCurrentUser() ?: return

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_USER_PRESENT,
            Intent.ACTION_POWER_CONNECTED,
            Intent.ACTION_POWER_DISCONNECTED,
            "android.intent.action.QUICKBOOT_POWERON",
            LocationTrackingService.ACTION_KEEPALIVE_ALARM -> {
                if (!currentUser.isSharingLocation) {
                    prefs.saveCurrentUser(currentUser.copy(isSharingLocation = true))
                }
                LocationTrackingService.start(context)
            }
            Intent.ACTION_SHUTDOWN, "android.intent.action.QUICKBOOT_POWEROFF" -> {
                // Phone is shutting down: alert all 1-on-1 friends and group channels
                try {
                    val realtime = RealtimeService.getInstance(context)
                    val alert = AlertMessage(
                        senderId = currentUser.id,
                        senderName = currentUser.displayName,
                        type = "PHONE_SHUTDOWN",
                        message = "⚠️ ${currentUser.displayName}'s phone is being turned off."
                    )
                    // Notify 1-on-1 friends
                    prefs.loadFriends().forEach { friend ->
                        realtime.broadcastAlert(friend.friendCode, alert)
                    }
                    // Notify groups
                    val payload = Gson().toJson(alert)
                    prefs.loadGroups().forEach { group ->
                        realtime.publish("pinpulse/v1/groups/${group.code}/events", payload)
                    }
                } catch (e: Exception) {
                    Log.e("BootReceiver", "Shutdown alert broadcast failed: ${e.message}")
                }
            }
        }
    }
}
