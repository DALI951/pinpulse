package com.pinpulse.radar.model

import com.google.gson.annotations.SerializedName

data class LocationPacket(
    @SerializedName("userId") val userId: String,
    @SerializedName("displayName") val displayName: String,
    @SerializedName("avatarBase64") val avatarBase64: String? = null,
    @SerializedName("latitude") val latitude: Double,
    @SerializedName("longitude") val longitude: Double,
    @SerializedName("speedKmh") val speedKmh: Float = 0f,
    @SerializedName("isSharingLocation") val isSharingLocation: Boolean = true,
    @SerializedName("timestamp") val timestamp: Long = System.currentTimeMillis()
)
