package com.pinpulse.radar.model

import com.google.gson.annotations.SerializedName
import java.util.UUID

data class AlertMessage(
    @SerializedName("id") val id: String = UUID.randomUUID().toString(),
    @SerializedName("senderId") val senderId: String = "",
    @SerializedName("senderName") val senderName: String = "Friend",
    @SerializedName("type") val type: String = "ALERT",
    @SerializedName("groupId") val groupId: String? = null,
    @SerializedName("message") val message: String = "",
    @SerializedName("timestamp") val timestamp: Long = System.currentTimeMillis()
) {
    @Suppress("USELESS_ELVIS")
    fun sanitize(): AlertMessage = copy(
        id = ((id as String?) ?: "").ifBlank { UUID.randomUUID().toString() },
        senderId = (senderId as String?) ?: "",
        senderName = ((senderName as String?) ?: "").ifBlank { "Friend" },
        type = ((type as String?) ?: "").ifBlank { "ALERT" },
        message = (message as String?) ?: ""
    )
}
