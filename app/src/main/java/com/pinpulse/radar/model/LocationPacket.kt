package com.pinpulse.radar.model

import com.google.gson.annotations.SerializedName

data class LocationPacket(
    @SerializedName("userId") val userId: String = "",
    @SerializedName("displayName") val displayName: String = "",
    @SerializedName("email") val email: String = "",
    @SerializedName("friendCode") val friendCode: String = "",
    @SerializedName("avatarBase64") val avatarBase64: String? = null,
    @SerializedName("statusMessage") val statusMessage: String? = null,
    @SerializedName("latitude") val latitude: Double = 0.0,
    @SerializedName("longitude") val longitude: Double = 0.0,
    @SerializedName("speedKmh") val speedKmh: Float = 0f,
    @SerializedName("batteryPercent") val batteryPercent: Int = 100,
    @SerializedName("isCharging") val isCharging: Boolean = false,
    @SerializedName("isSharingLocation") val isSharingLocation: Boolean = true,
    @SerializedName("timestamp") val timestamp: Long = System.currentTimeMillis()
) {
    @Suppress("USELESS_ELVIS")
    fun sanitize(): LocationPacket = copy(
        userId = (userId as String?) ?: "",
        displayName = (displayName as String?) ?: "",
        email = (email as String?) ?: "",
        friendCode = (friendCode as String?) ?: ""
    )
}
