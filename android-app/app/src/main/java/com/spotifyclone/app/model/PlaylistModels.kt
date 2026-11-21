package com.spotifyclone.app.model

import com.google.gson.annotations.SerializedName

data class TrackDto(
    @SerializedName("id") val id: Long? = null,
    @SerializedName("youtubeId") val youtubeId: String,
    @SerializedName("title") val title: String,
    @SerializedName("artist") val artist: String?,
    @SerializedName("durationSeconds") val durationSeconds: Long? = null,
    @SerializedName("thumbnailUrl") val thumbnailUrl: String? = null,
    @SerializedName("position") val position: Int? = 0,
    // Spotify catalog id when this track originated from Spotify metadata.
    // youtubeId stays "" until NewPipe resolves the YouTube Music audio match.
    @SerializedName("spotifyId") val spotifyId: String? = null
)

data class PlaylistRequest(
    @SerializedName("title") val title: String,
    @SerializedName("description") val description: String? = null,
    @SerializedName("coverUrl") val coverUrl: String? = null
)

data class PlaylistDto(
    @SerializedName("id") val id: Long,
    @SerializedName("title") val title: String,
    @SerializedName("description") val description: String?,
    @SerializedName("coverUrl") val coverUrl: String? = null,
    @SerializedName("createdAt") val createdAt: String?,
    @SerializedName("updatedAt") val updatedAt: String?,
    @SerializedName("tracks") val tracks: List<TrackDto> = emptyList()
)

data class ApiResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("message") val message: String
)

// YouTube Music artist profile
data class ArtistDto(
    val name: String,
    val avatarUrl: String?,
    val bannerUrl: String?,
    val description: String?,
    val channelUrl: String? = null
)

// Artist profile + their track catalogue
data class ArtistChannel(
    val artist: ArtistDto,
    val tracks: List<TrackDto>
)

// Album with songs
data class AlbumDto(
    val id: String? = null,
    val name: String,
    val artistName: String? = null,
    val year: String? = null,
    val coverUrl: String?,
    val tracks: List<TrackDto> = emptyList(),
    val trackCount: Int? = null,
    val channelUrl: String? = null
)
