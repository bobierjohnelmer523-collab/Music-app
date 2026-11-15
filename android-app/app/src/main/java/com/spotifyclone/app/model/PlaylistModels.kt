package com.spotifyclone.app.model

import com.google.gson.annotations.SerializedName

data class TrackDto(
    @SerializedName("id") val id: Long? = null,
    @SerializedName("youtubeId") val youtubeId: String,
    @SerializedName("title") val title: String,
    @SerializedName("artist") val artist: String?,
    @SerializedName("durationSeconds") val durationSeconds: Long?,
    @SerializedName("thumbnailUrl") val thumbnailUrl: String?,
    @SerializedName("position") val position: Int? = 0
)

data class PlaylistRequest(
    @SerializedName("title") val title: String,
    @SerializedName("description") val description: String? = null
)

data class PlaylistDto(
    @SerializedName("id") val id: Long,
    @SerializedName("title") val title: String,
    @SerializedName("description") val description: String?,
    @SerializedName("createdAt") val createdAt: String?,
    @SerializedName("updatedAt") val updatedAt: String?,
    @SerializedName("tracks") val tracks: List<TrackDto> = emptyList()
)

data class ApiResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("message") val message: String
)
