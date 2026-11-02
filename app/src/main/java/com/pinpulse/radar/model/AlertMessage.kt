package com.pinpulse.radar.model

import com.google.gson.annotations.SerializedName

data class AlertMessage(
    @SerializedName("id") val id: String = java.util.UUID.randomUUID().toString(),
    @SerializedName("senderId") val senderId: String,
    @SerializedName("senderName") val senderName: String,
    @SerializedName("type") val type: String, // "LOCATION_OFF", "KICKED", "AVATAR_CHANGED", "FRIEND_REQUEST"
    @SerializedName("groupId") val groupId: String? = null,
    @SerializedName("message") val message: String,
    @SerializedName("timestamp") val timestamp: Long = System.currentTimeMillis()
)
