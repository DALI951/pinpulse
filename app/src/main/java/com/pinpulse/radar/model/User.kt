package com.pinpulse.radar.model

import com.google.gson.annotations.SerializedName

data class User(
    @SerializedName("id") val id: String,
    @SerializedName("email") val email: String,
    @SerializedName("displayName") val displayName: String,
    @SerializedName("avatarBase64") val avatarBase64: String? = null,
    @SerializedName("friendCode") val friendCode: String,
    @SerializedName("isSharingLocation") val isSharingLocation: Boolean = true,
    @SerializedName("latitude") val latitude: Double = 0.0,
    @SerializedName("longitude") val longitude: Double = 0.0,
    @SerializedName("speedKmh") val speedKmh: Float = 0f,
    @SerializedName("lastUpdated") val lastUpdated: Long = System.currentTimeMillis()
) {
    val isLive: Boolean
        get() = isSharingLocation && (System.currentTimeMillis() - lastUpdated < 60_000)

    val lastSeenText: String
        get() {
            if (!isSharingLocation) return "Location Sharing Paused"
            val diffSec = (System.currentTimeMillis() - lastUpdated) / 1000
            return when {
                diffSec < 15 -> "Live now"
                diffSec < 60 -> "${diffSec}s ago"
                diffSec < 3600 -> "${diffSec / 60}m ago"
                else -> "${diffSec / 3600}h ago"
            }
        }
}
