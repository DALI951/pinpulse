package com.pinpulse.radar.model

import com.google.gson.annotations.SerializedName

data class TrailPoint(
    @SerializedName("latitude") val latitude: Double = 0.0,
    @SerializedName("longitude") val longitude: Double = 0.0,
    @SerializedName("speedKmh") val speedKmh: Float = 0f,
    @SerializedName("timestamp") val timestamp: Long = System.currentTimeMillis()
)

data class User(
    @SerializedName("id") val id: String = "",
    @SerializedName("email") val email: String = "",
    @SerializedName("displayName") val displayName: String = "User",
    @SerializedName("avatarBase64") val avatarBase64: String? = null,
    @SerializedName("friendCode") val friendCode: String = "",
    @SerializedName("statusMessage") val statusMessage: String? = null,
    @SerializedName("isSharingLocation") val isSharingLocation: Boolean = true,
    @SerializedName("isGpsEnabled") val isGpsEnabled: Boolean = true,
    @SerializedName("latitude") val latitude: Double = 0.0,
    @SerializedName("longitude") val longitude: Double = 0.0,
    @SerializedName("speedKmh") val speedKmh: Float = 0f,
    @SerializedName("batteryPercent") val batteryPercent: Int = 100,
    @SerializedName("isCharging") val isCharging: Boolean = false,
    @SerializedName("lastUpdated") val lastUpdated: Long = System.currentTimeMillis(),
    // Subscription & SaaS Plan Governance
    @SerializedName("subscriptionPlan") val subscriptionPlan: String = "DEMO", // DEMO, FAMILY, BUSINESS, CUSTOM
    @SerializedName("accountStatus") val accountStatus: String = "ACTIVE", // ACTIVE, DEACTIVATED, BANNED, DELETED, EXPIRED
    @SerializedName("subscriptionStartAt") val subscriptionStartAt: Long = System.currentTimeMillis(),
    @SerializedName("subscriptionExpiresAt") val subscriptionExpiresAt: Long = System.currentTimeMillis() + 7L * 24L * 3600L * 1000L,
    @SerializedName("maxFriends") val maxFriends: Int = 1,
    @SerializedName("maxGroups") val maxGroups: Int = 0,
    @SerializedName("rateLimitSec") val rateLimitSec: Int = 3,
    @SerializedName("monthlyRateUsd") val monthlyRateUsd: Double = 0.0,
    @SerializedName("friendsCount") val friendsCount: Int = 0,
    @SerializedName("groupsCount") val groupsCount: Int = 0
) {
    val isLive: Boolean
        get() = isSharingLocation && (System.currentTimeMillis() - lastUpdated < 60_000)

    val lastSeenText: String
        get() {
            if (!isSharingLocation) return "Offline"
            val diffSec = (System.currentTimeMillis() - lastUpdated) / 1000
            val base = when {
                diffSec < 15 -> "Live now"
                diffSec < 60 -> "${diffSec}s ago"
                diffSec < 3600 -> "${diffSec / 60}m ago"
                else -> "${diffSec / 3600}h ago"
            }
            return if (!isGpsEnabled) "$base • GPS OFF" else base
        }

    val effectiveExpiresAt: Long
        get() = if (subscriptionExpiresAt > 1_000_000_000_000L) {
            subscriptionExpiresAt
        } else {
            (if (subscriptionStartAt > 1_000_000_000_000L) subscriptionStartAt else System.currentTimeMillis()) + 7L * 24L * 3600L * 1000L
        }

    val isLifetime: Boolean
        get() = effectiveExpiresAt >= 3_000_000_000_000L || subscriptionPlan.equals("LIFETIME", ignoreCase = true)

    val remainingSubscriptionMs: Long
        get() = (effectiveExpiresAt - System.currentTimeMillis()).coerceAtLeast(0L)

    val remainingDaysCount: Int
        get() = if (isLifetime) 99999 else kotlin.math.ceil(remainingSubscriptionMs.toDouble() / (24.0 * 3600.0 * 1000.0)).toInt()

    val isSubscriptionExpired: Boolean
        get() = !isLifetime && (accountStatus.equals("EXPIRED", ignoreCase = true) || remainingSubscriptionMs <= 0L)

    val remainingTimeFormatted: String
        get() {
            if (isLifetime && accountStatus.equals("ACTIVE", ignoreCase = true)) {
                return "♾️ Lifetime Access"
            }
            val ms = remainingSubscriptionMs
            if (ms <= 0L || accountStatus.equals("EXPIRED", ignoreCase = true)) return "Expired (0d left)"
            val totalHours = ms / (3600L * 1000L)
            val days = totalHours / 24L
            val hours = totalHours % 24L
            val mins = (ms / (60L * 1000L)) % 60L
            return when {
                days >= 3650 -> "♾️ Lifetime Access"
                days > 0 -> "${days}d ${hours}h left"
                hours > 0 -> "${hours}h ${mins}m left"
                else -> "${mins.coerceAtLeast(1)}m left"
            }
        }

    val planDisplayName: String
        get() = when {
            isLifetime && subscriptionPlan.uppercase() == "DEMO" -> "Lifetime Pack ♾️"
            isLifetime -> "${subscriptionPlan.uppercase()} • Lifetime ♾️"
            subscriptionPlan.uppercase() == "FAMILY" -> "Family Pack"
            subscriptionPlan.uppercase() == "BUSINESS" -> "Business Pack"
            subscriptionPlan.uppercase() == "CUSTOM" -> "Custom Enterprise"
            else -> "Demo Pack (7-Day Free)"
        }

    @Suppress("USELESS_ELVIS")
    fun sanitize(): User {
        val cleanPlan = ((subscriptionPlan as String?) ?: "DEMO").uppercase().ifBlank { "DEMO" }
        val cleanStatus = ((accountStatus as String?) ?: "ACTIVE").uppercase().ifBlank { "ACTIVE" }
        val now = System.currentTimeMillis()
        val cleanStart = if (subscriptionStartAt > 1_000_000_000_000L) subscriptionStartAt else now
        val cleanExpires = if (subscriptionExpiresAt > 1_000_000_000_000L) {
            subscriptionExpiresAt
        } else {
            cleanStart + 7L * 24L * 3600L * 1000L
        }
        val defaultMaxFriends = when (cleanPlan) {
            "FAMILY" -> 10
            "BUSINESS" -> 50
            "CUSTOM" -> if (maxFriends > 0) maxFriends else 100
            else -> 1
        }
        val defaultMaxGroups = when (cleanPlan) {
            "FAMILY" -> 2
            "BUSINESS" -> 10
            "CUSTOM" -> if (maxGroups >= 0) maxGroups else 25
            else -> 0
        }
        return copy(
            id = (id as String?) ?: "",
            email = (email as String?) ?: "",
            displayName = ((displayName as String?) ?: "").ifBlank { "User" },
            friendCode = (friendCode as String?) ?: "",
            subscriptionPlan = cleanPlan,
            accountStatus = cleanStatus,
            subscriptionStartAt = cleanStart,
            subscriptionExpiresAt = cleanExpires,
            maxFriends = if (maxFriends > 0) maxFriends else defaultMaxFriends,
            maxGroups = if (maxGroups >= 0 && cleanPlan == "CUSTOM") maxGroups else if (maxGroups > 0) maxGroups else defaultMaxGroups,
            rateLimitSec = rateLimitSec.coerceIn(1, 300)
        )
    }
}
