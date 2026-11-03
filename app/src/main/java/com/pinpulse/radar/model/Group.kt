package com.pinpulse.radar.model

import com.google.gson.annotations.SerializedName

data class Group(
    @SerializedName("id") val id: String = "",
    @SerializedName("name") val name: String = "Group",
    @SerializedName("code") val code: String = "",
    @SerializedName("creatorId") val creatorId: String = "",
    @SerializedName("memberIds") val memberIds: List<String> = emptyList(),
    @SerializedName("memberAvatars") val memberAvatars: Map<String, String> = emptyMap(),
    @SerializedName("createdAt") val createdAt: Long = System.currentTimeMillis()
) {
    @Suppress("USELESS_ELVIS")
    fun sanitize(): Group = copy(
        id = (id as String?) ?: "",
        name = ((name as String?) ?: "").ifBlank { "Group" },
        code = (code as String?) ?: "",
        creatorId = (creatorId as String?) ?: "",
        memberIds = (memberIds as List<String>?) ?: emptyList(),
        memberAvatars = (memberAvatars as Map<String, String>?) ?: emptyMap()
    )
}
