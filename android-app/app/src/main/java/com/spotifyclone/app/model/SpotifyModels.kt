package com.spotifyclone.app.model

import com.google.gson.annotations.SerializedName

// ---------- Spotify Web API models (api.spotify.com/v1) ----------

data class SpotifyImage(
    @SerializedName("url") val url: String?,
    @SerializedName("width") val width: Int?,
    @SerializedName("height") val height: Int?
)

data class SpotifyOwner(
    @SerializedName("display_name") val displayName: String?,
    @SerializedName("id") val id: String?
)

data class SpotifyExternalUrls(
    @SerializedName("spotify") val spotify: String?
)

/** Simplified playlist object returned by /me/playlists and /search */
data class SpotifyPlaylistSummary(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("description") val description: String?,
    @SerializedName("images") val images: List<SpotifyImage>?,
    @SerializedName("owner") val owner: SpotifyOwner?,
    @SerializedName("tracks") val tracks: SpotifyTracksRef?,
    @SerializedName("uri") val uri: String?,
    @SerializedName("external_urls") val externalUrls: SpotifyExternalUrls?
)

/** Reference object for track count on playlist summaries */
data class SpotifyTracksRef(
    @SerializedName("total") val total: Int?,
    @SerializedName("href") val href: String?
)

data class SpotifyArtistRaw(
    @SerializedName("name") val name: String?
)

data class SpotifyAlbumRaw(
    @SerializedName("name") val name: String?,
    @SerializedName("images") val images: List<SpotifyImage>?
)

data class SpotifyTrackRaw(
    @SerializedName("id") val id: String?,
    @SerializedName("name") val name: String?,
    @SerializedName("uri") val uri: String?,
    @SerializedName("duration_ms") val durationMs: Long?,
    @SerializedName("artists") val artists: List<SpotifyArtistRaw>?,
    @SerializedName("album") val album: SpotifyAlbumRaw?
)

data class SpotifyPlaylistTrackItem(
    @SerializedName("track") val track: SpotifyTrackRaw?
)

data class SpotifyPaging<T>(
    @SerializedName("items") val items: List<T>?,
    @SerializedName("next") val next: String?,
    @SerializedName("total") val total: Int?
)

/** Full playlist object returned by /playlists/{id} */
data class SpotifyPlaylistDetail(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("description") val description: String?,
    @SerializedName("images") val images: List<SpotifyImage>?,
    @SerializedName("owner") val owner: SpotifyOwner?,
    @SerializedName("tracks") val tracks: SpotifyPaging<SpotifyPlaylistTrackItem>?,
    @SerializedName("uri") val uri: String?,
    @SerializedName("external_urls") val externalUrls: SpotifyExternalUrls?,
    @SerializedName("snapshot_id") val snapshotId: String?
)

data class SpotifySearchResult(
    @SerializedName("playlists") val playlists: SpotifyPaging<SpotifyPlaylistSummary>?
)

data class SpotifyUser(
    @SerializedName("id") val id: String?,
    @SerializedName("display_name") val displayName: String?,
    @SerializedName("email") val email: String?
)
