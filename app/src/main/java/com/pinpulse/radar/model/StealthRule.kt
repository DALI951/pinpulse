package com.pinpulse.radar.model

import com.google.gson.annotations.SerializedName

data class StealthRule(
    @SerializedName("mode") val mode: String = "LIVE", // "LIVE", "FROZEN", "DELAYED", "HIDDEN"
    @SerializedName("canRing") val canRing: Boolean = true,
    @SerializedName("frozenLat") val frozenLat: Double = 0.0,
    @SerializedName("frozenLng") val frozenLng: Double = 0.0,
    @SerializedName("delayMinutes") val delayMinutes: Int = 15
)
