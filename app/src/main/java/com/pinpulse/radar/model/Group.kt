package com.pinpulse.radar.model

import com.google.gson.annotations.SerializedName

data class Group(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("code") val code: String,
    @SerializedName("creatorId") val creatorId: String,
    @SerializedName("memberIds") val memberIds: List<String> = emptyList(),
    // Custom avatars assigned by Group Leader for members in this group
    @SerializedName("memberAvatars") val memberAvatars: Map<String, String> = emptyMap(),
    @SerializedName("createdAt") val createdAt: Long = System.currentTimeMillis()
)
