package com.spotifyclone.app.spotify

import android.util.Log
import com.spotifyclone.app.model.TrackDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Fetches PUBLIC Spotify playlists without any API credentials by parsing the
 * anonymous embed page (open.spotify.com/embed/playlist/{id}), which embeds a
 * __NEXT_DATA__ JSON blob containing the playlist name, cover art and up to
 * 50 tracks (title, artists, duration, spotify URI).
 *
 * Private playlists cannot be fetched this way — they require OAuth login
 * (which itself now requires a Premium developer account).
 */
object SpotifyEmbedFetcher {

    data class EmbedPlaylist(
        val id: String,
        val name: String,
        val ownerName: String?,
        val coverUrl: String?,
        val tracks: List<TrackDto>
    )

    data class EmbedArtist(
        val id: String,
        val name: String,
        val followers: String?,
        val imageUrl: String?,
        val popularTracks: List<TrackDto>
    )

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    private val NEXT_DATA_REGEX =
        Regex("<script id=\"__NEXT_DATA__\" type=\"application/json\">(.*?)</script>", RegexOption.DOT_MATCHES_ALL)

    suspend fun fetchPlaylist(playlistId: String): EmbedPlaylist? = withContext(Dispatchers.IO) {
        try {
            val url = "https://open.spotify.com/embed/playlist/$playlistId"
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36")
                .build()

            val html = httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                response.body?.string() ?: return@withContext null
            }

            val match = NEXT_DATA_REGEX.find(html) ?: return@withContext null
            val root = JSONObject(match.groupValues[1])
            val entity = root
                .optJSONObject("props")
                ?.optJSONObject("pageProps")
                ?.optJSONObject("state")
                ?.optJSONObject("data")
                ?.optJSONObject("entity")
                ?: return@withContext null

            // Private/unavailable playlists have no usable entity or trackList
            val trackListJson = entity.optJSONArray("trackList") ?: return@withContext null

            val coverUrl = entity.optJSONObject("coverArt")
                ?.optJSONArray("sources")
                ?.let { sources ->
                    var best: String? = null
                    var bestSize = -1
                    for (i in 0 until sources.length()) {
                        val src = sources.optJSONObject(i) ?: continue
                        val w = src.optInt("width", 0) * src.optInt("height", 0)
                        val u = src.optString("url").takeIf { it.isNotBlank() }
                        if (u != null && w > bestSize) {
                            bestSize = w
                            best = u
                        }
                    }
                    best ?: sources.optJSONObject(0)?.optString("url")?.takeIf { it.isNotBlank() }
                }

            val tracks = mutableListOf<TrackDto>()
            for (i in 0 until trackListJson.length()) {
                val t = trackListJson.optJSONObject(i) ?: continue
                val spotifyUri = t.optString("uri")
                val spotifyId = spotifyUri.substringAfterLast(':', "")
                val title = t.optString("title").takeIf { it.isNotBlank() } ?: continue
                val artist = t.optString("subtitle").takeIf { it.isNotBlank() }
                val durationMs = t.optLong("duration", 0L)
                val trackArtwork = t.optJSONObject("coverArt")?.optJSONArray("sources")
                    ?.let { sources ->
                        (0 until sources.length()).mapNotNull { index ->
                            sources.optJSONObject(index)?.optString("url")?.takeIf(String::isNotBlank)
                        }.lastOrNull()
                    } ?: t.optString("imageUrl").takeIf(String::isNotBlank)

                tracks.add(
                    TrackDto(
                        youtubeId = "",
                        spotifyId = spotifyId.ifBlank { null },
                        title = title,
                        artist = artist,
                        durationSeconds = if (durationMs > 0) durationMs / 1000 else null,
                        thumbnailUrl = trackArtwork,
                        position = i
                    )
                )
            }

            if (tracks.isEmpty()) return@withContext null

            EmbedPlaylist(
                id = entity.optString("id", playlistId),
                name = entity.optString("name").ifBlank { entity.optString("title") },
                ownerName = entity.optString("subtitle").takeIf { it.isNotBlank() },
                coverUrl = coverUrl,
                tracks = tracks
            )
        } catch (e: Exception) {
            Log.w("SpotifyEmbed", "fetchPlaylist failed for $playlistId", e)
            null
        }
    }

    /**
     * Fetch artist popular songs from Spotify's public artist page.
     * This parses the __NEXT_DATA__ JSON from the artist page.
     */
    suspend fun fetchArtist(artistId: String): EmbedArtist? = withContext(Dispatchers.IO) {
        try {
            val url = "https://open.spotify.com/artist/$artistId"
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36")
                .build()

            val html = httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                response.body?.string() ?: return@withContext null
            }

            val match = NEXT_DATA_REGEX.find(html) ?: return@withContext null
            val root = JSONObject(match.groupValues[1])

            // Navigate to artist data
            val data = root
                .optJSONObject("props")
                ?.optJSONObject("pageProps")
                ?.optJSONObject("state")
                ?.optJSONObject("data")
                ?: return@withContext null

            val entity = data.optJSONObject("entity") ?: return@withContext null
            val artistData = entity.optJSONObject("artist") ?: entity

            val name = artistData.optString("name").takeIf { it.isNotBlank() } ?: "Unknown Artist"
            val followers = artistData.optJSONObject("stats")?.optString("followers")?.takeIf { it.isNotBlank() }
            val imageUrl = artistData.optJSONObject("visuals")
                ?.optJSONObject("avatarImage")
                ?.optJSONArray("sources")
                ?.let { sources ->
                    var best: String? = null
                    var bestSize = -1
                    for (i in 0 until sources.length()) {
                        val src = sources.optJSONObject(i) ?: continue
                        val w = src.optInt("width", 0) * src.optInt("height", 0)
                        val u = src.optString("url").takeIf { it.isNotBlank() }
                        if (u != null && w > bestSize) {
                            bestSize = w
                            best = u
                        }
                    }
                    best ?: sources.optJSONObject(0)?.optString("url")?.takeIf { it.isNotBlank() }
                }

            // Extract popular tracks
            val tracks = mutableListOf<TrackDto>()
            val popular = artistData.optJSONObject("topTracks")?.optJSONArray("items")
            if (popular != null) {
                for (i in 0 until popular.length()) {
                    val t = popular.optJSONObject(i) ?: continue
                    val track = t.optJSONObject("track") ?: continue
                    val spotifyUri = track.optString("uri")
                    val spotifyId = spotifyUri.substringAfterLast(':', "")
                    val title = track.optString("name").takeIf { it.isNotBlank() } ?: continue
                    val artistName = track.optJSONArray("artists")
                        ?.optJSONObject(0)
                        ?.optString("name")
                        ?.takeIf { it.isNotBlank() }
                    val durationMs = track.optLong("duration", 0L)
                    val thumbnailUrl = track.optJSONObject("album")
                        ?.optJSONArray("images")
                        ?.optJSONObject(0)
                        ?.optString("url")
                        ?.takeIf { it.isNotBlank() }

                    tracks.add(
                        TrackDto(
                            youtubeId = "",
                            spotifyId = spotifyId.ifBlank { null },
                            title = title,
                            artist = artistName ?: name,
                            durationSeconds = if (durationMs > 0) durationMs / 1000 else null,
                            thumbnailUrl = thumbnailUrl,
                            position = i
                        )
                    )
                }
            }

            EmbedArtist(
                id = artistId,
                name = name,
                followers = followers,
                imageUrl = imageUrl,
                popularTracks = tracks
            )
        } catch (e: Exception) {
            Log.w("SpotifyEmbed", "fetchArtist failed for $artistId", e)
            null
        }
    }
}
