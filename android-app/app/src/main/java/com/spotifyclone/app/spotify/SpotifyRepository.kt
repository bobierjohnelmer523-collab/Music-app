package com.spotifyclone.app.spotify

import android.util.Log
import com.spotifyclone.app.model.SpotifyPaging
import com.spotifyclone.app.model.SpotifyPlaylistDetail
import com.spotifyclone.app.model.SpotifyPlaylistSummary
import com.spotifyclone.app.model.SpotifyPlaylistTrackItem
import com.spotifyclone.app.model.SpotifyTrackRaw
import com.spotifyclone.app.model.SpotifyUser
import com.spotifyclone.app.model.TrackDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * High-level access to the Spotify catalog.
 *
 * Spotify supplies metadata only (title, artist, artwork, playlist structure).
 * Audio always comes from NewPipe/YouTube Music at playback time.
 */
object SpotifyRepository {

    private const val TAG = "SpotifyRepository"
    private const val MAX_PLAYLIST_TRACKS = 500

    // ---------------- Link parsing ----------------

    private val PLAYLIST_ID_PATTERNS = listOf(
        Regex("spotify:playlist:([A-Za-z0-9]+)"),
        Regex("open\\.spotify\\.com/playlist/([A-Za-z0-9]+)"),
        Regex("^([A-Za-z0-9]{20,})$") // bare playlist id
    )

    /** Extract a Spotify playlist ID from a URL, URI, or bare ID. Null if not a playlist link. */
    fun parsePlaylistId(input: String): String? {
        val text = input.trim()
        for (pattern in PLAYLIST_ID_PATTERNS) {
            val match = pattern.find(text) ?: continue
            val candidate = match.groupValues.getOrNull(1)
            if (candidate != null && candidate.matches(Regex("[A-Za-z0-9]+"))) {
                return candidate
            }
        }
        return null
    }

    /** True when the input looks like any Spotify link/URI we may support. */
    fun isSpotifyLink(input: String): Boolean {
        val t = input.trim().lowercase()
        return t.contains("spotify.com/") ||
            t.startsWith("spotify:") ||
            t.startsWith("spotify.link/")
    }

    /** Resolve spotify.link short URLs to the final open.spotify.com URL. */
    suspend fun resolveShortLink(input: String): String = withContext(Dispatchers.IO) {
        try {
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
            val request = okhttp3.Request.Builder().url(input.trim()).build()
            client.newCall(request).execute().use { response ->
                response.request.url.toString()
            }
        } catch (e: Exception) {
            input.trim()
        }
    }

    /** Parse a playlist id, resolving spotify.link short URLs first. */
    suspend fun parsePlaylistIdAsync(input: String): String? {
        parsePlaylistId(input)?.let { return it }
        if (input.trim().lowercase().startsWith("spotify.link/")) {
            val resolved = resolveShortLink(input)
            return parsePlaylistId(resolved)
        }
        return null
    }

    // ---------------- Auth-gated API calls ----------------

    private suspend fun authHeader(): String? {
        val token = SpotifyAuthManager.getAccessToken() ?: return null
        return "Bearer $token"
    }

    suspend fun getCurrentUser(): SpotifyUser? = withContext(Dispatchers.IO) {
        val auth = authHeader() ?: return@withContext null
        try {
            SpotifyApi.service.getMe(auth)
        } catch (e: Exception) {
            Log.w(TAG, "getMe failed", e)
            null
        }
    }

    suspend fun getMyPlaylists(): List<SpotifyPlaylistSummary> = withContext(Dispatchers.IO) {
        val auth = authHeader() ?: return@withContext emptyList()
        val all = mutableListOf<SpotifyPlaylistSummary>()
        var offset = 0
        try {
            while (all.size < 200) {
                val page = SpotifyApi.service.getMyPlaylists(auth, limit = 50, offset = offset)
                val items = page.items.orEmpty()
                all.addAll(items)
                if (items.isEmpty() || page.next == null) break
                offset += items.size
            }
        } catch (e: Exception) {
            Log.w(TAG, "getMyPlaylists failed", e)
        }
        all
    }

    suspend fun getPlaylistDetail(playlistId: String): SpotifyPlaylistDetail? =
        withContext(Dispatchers.IO) {
            val auth = authHeader() ?: return@withContext null
            try {
                SpotifyApi.service.getPlaylist(auth, playlistId)
            } catch (e: Exception) {
                Log.w(TAG, "getPlaylist failed for $playlistId", e)
                null
            }
        }

    /** Fetch every track of a playlist (paginated), converted to playable TrackDtos. */
    suspend fun getPlaylistTracks(
        playlistId: String,
        maxTracks: Int = MAX_PLAYLIST_TRACKS
    ): List<TrackDto> = withContext(Dispatchers.IO) {
        val auth = authHeader() ?: return@withContext emptyList()
        val tracks = mutableListOf<TrackDto>()
        var offset = 0
        try {
            while (tracks.size < maxTracks) {
                val page: SpotifyPaging<SpotifyPlaylistTrackItem> =
                    SpotifyApi.service.getPlaylistTracks(
                        auth = auth,
                        id = playlistId,
                        limit = 100,
                        offset = offset
                    )
                val items = page.items.orEmpty()
                for (item in items) {
                    val dto = item.track?.toTrackDto(tracks.size) ?: continue
                    tracks.add(dto)
                    if (tracks.size >= maxTracks) break
                }
                if (items.isEmpty() || page.next == null) break
                offset += items.size
            }
        } catch (e: Exception) {
            Log.w(TAG, "getPlaylistTracks failed for $playlistId", e)
        }
        tracks
    }

    suspend fun searchPlaylists(query: String, limit: Int = 10): List<SpotifyPlaylistSummary> =
        withContext(Dispatchers.IO) {
            val auth = authHeader() ?: return@withContext emptyList()
            try {
                SpotifyApi.service.searchPlaylists(auth, query, "playlist", limit)
                    .playlists?.items.orEmpty()
            } catch (e: Exception) {
                Log.w(TAG, "searchPlaylists failed", e)
                emptyList()
            }
        }

    /** Find an official/editorial playlist whose name best matches [query]. */
    suspend fun findPlaylistIdByQuery(query: String): String? {
        val results = searchPlaylists(query, limit = 10)
        if (results.isEmpty()) return null
        val q = query.trim().lowercase()
        // Prefer exact/normalized name match, then substring match
        return results.firstOrNull {
            it.name.trim().lowercase() == q
        }?.id ?: results.firstOrNull {
            val n = it.name.trim().lowercase()
            n.contains(q) || q.contains(n)
        }?.id ?: results.firstOrNull()?.id
    }

    /** Unified playlist content: official API when logged in, public embed otherwise. */
    data class PlaylistContent(
        val id: String,
        val title: String,
        val description: String?,
        val coverUrl: String?,
        val ownerName: String?,
        val tracks: List<TrackDto>,
        val viaApi: Boolean
    )

    /**
     * Load a playlist by Spotify ID. Uses the Web API when the user is logged
     * in (works for private playlists, full tracklist); otherwise falls back to
     * the anonymous embed page (public playlists, up to ~50 tracks).
     */
    suspend fun getPlaylistContent(playlistId: String): PlaylistContent? {
        if (SpotifyAuthManager.isLoggedIn()) {
            val detail = getPlaylistDetail(playlistId)
            if (detail != null) {
                val tracks = getPlaylistTracks(playlistId)
                if (tracks.isNotEmpty()) {
                    return PlaylistContent(
                        id = detail.id,
                        title = detail.name,
                        description = detail.description,
                        coverUrl = detail.coverUrl(),
                        ownerName = detail.owner?.displayName,
                        tracks = tracks,
                        viaApi = true
                    )
                }
            }
        }

        val embed = SpotifyEmbedFetcher.fetchPlaylist(playlistId) ?: return null
        return PlaylistContent(
            id = embed.id,
            title = embed.name,
            description = embed.ownerName?.let { "by $it" },
            coverUrl = embed.coverUrl,
            ownerName = embed.ownerName,
            tracks = embed.tracks,
            viaApi = false
        )
    }

    /** Lightweight metadata lookup (title/cover/description) without tracks. */
    suspend fun getPlaylistMeta(playlistId: String): PlaylistContent? {
        // Embed first — single anonymous request, works for all public playlists
        SpotifyEmbedFetcher.fetchPlaylist(playlistId)?.let { embed ->
            return PlaylistContent(
                id = embed.id,
                title = embed.name,
                description = embed.ownerName?.let { "by $it" },
                coverUrl = embed.coverUrl,
                ownerName = embed.ownerName,
                tracks = emptyList(),
                viaApi = false
            )
        }
        // Private playlist fallback via API (requires login)
        if (SpotifyAuthManager.isLoggedIn()) {
            getPlaylistDetail(playlistId)?.let { detail ->
                return PlaylistContent(
                    id = detail.id,
                    title = detail.name,
                    description = detail.description,
                    coverUrl = detail.coverUrl(),
                    ownerName = detail.owner?.displayName,
                    tracks = emptyList(),
                    viaApi = true
                )
            }
        }
        return null
    }

    // ---------------- Conversion ----------------

    /** Spotify metadata -> playable track. youtubeId stays blank until NewPipe resolves it. */
    fun SpotifyTrackRaw.toTrackDto(position: Int): TrackDto? {
        val trackId = id ?: return null
        val trackName = name?.takeIf { it.isNotBlank() } ?: return null
        val artistNames = artists?.mapNotNull { it.name?.takeIf { n -> n.isNotBlank() } }
            ?.joinToString(", ")?.takeIf { it.isNotBlank() }
        val artwork = album?.images
            ?.filter { it.url != null }
            ?.maxByOrNull { it.width ?: 0 }?.url
            ?: album?.images?.lastOrNull()?.url

        return TrackDto(
            youtubeId = "",
            spotifyId = trackId,
            title = trackName,
            artist = artistNames,
            durationSeconds = durationMs?.let { it / 1000 },
            thumbnailUrl = artwork,
            position = position
        )
    }

}

/** Largest available cover image URL for a playlist summary. */
fun SpotifyPlaylistSummary.coverUrl(): String? =
    images?.maxByOrNull { it.width ?: 0 }?.url ?: images?.lastOrNull()?.url

/** Largest available cover image URL for a playlist detail. */
fun SpotifyPlaylistDetail.coverUrl(): String? =
    images?.maxByOrNull { it.width ?: 0 }?.url ?: images?.lastOrNull()?.url
