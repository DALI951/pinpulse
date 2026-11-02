package com.pinpulse.radar.model

import com.google.gson.annotations.SerializedName

data class FriendRequest(
    @SerializedName("id") val id: String,
    @SerializedName("fromUserId") val fromUserId: String,
    @SerializedName("fromUserName") val fromUserName: String,
    @SerializedName("fromUserEmail") val fromUserEmail: String,
    @SerializedName("fromUserAvatar") val fromUserAvatar: String? = null,
    @SerializedName("fromFriendCode") val fromFriendCode: String = "",
    @SerializedName("toUserId") val toUserId: String = "",
    @SerializedName("toFriendCode") val toFriendCode: String = "",
    @SerializedName("status") val status: String = "PENDING", // PENDING, ACCEPTED, DECLINED
    @SerializedName("timestamp") val timestamp: Long = System.currentTimeMillis()
)
