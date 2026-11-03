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
    @SerializedName("isGpsEnabled") val isGpsEnabled: Boolean = true,
    @SerializedName("timestamp") val timestamp: Long = System.currentTimeMillis(),
    // Subscription & SaaS Plan Telemetry
    @SerializedName("subscriptionPlan") val subscriptionPlan: String = "DEMO",
    @SerializedName("accountStatus") val accountStatus: String = "ACTIVE",
    @SerializedName("subscriptionExpiresAt") val subscriptionExpiresAt: Long = 0L,
    @SerializedName("maxFriends") val maxFriends: Int = 1,
    @SerializedName("maxGroups") val maxGroups: Int = 0,
    @SerializedName("rateLimitSec") val rateLimitSec: Int = 3,
    @SerializedName("monthlyRateUsd") val monthlyRateUsd: Double = 0.0,
    @SerializedName("friendsCount") val friendsCount: Int = 0,
    @SerializedName("groupsCount") val groupsCount: Int = 0
) {
    @Suppress("USELESS_ELVIS")
    fun sanitize(): LocationPacket = copy(
        userId = (userId as String?) ?: "",
        displayName = (displayName as String?) ?: "",
        email = (email as String?) ?: "",
        friendCode = (friendCode as String?) ?: "",
        subscriptionPlan = ((subscriptionPlan as String?) ?: "DEMO").ifBlank { "DEMO" },
        accountStatus = ((accountStatus as String?) ?: "ACTIVE").ifBlank { "ACTIVE" }
    )
}
