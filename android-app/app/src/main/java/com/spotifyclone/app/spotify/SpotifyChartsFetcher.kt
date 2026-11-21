package com.spotifyclone.app.spotify

import android.util.Log
import com.spotifyclone.app.model.TrackDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Official Spotify charts & editorial playlists.
 *
 * All entries are public Spotify playlists fetched through the anonymous
 * embed page (SpotifyEmbedFetcher) — no login or API quota required.
 * Artwork/duration gaps are enriched via the free iTunes Search API.
 */
object SpotifyChartsFetcher {

    data class ChartDescriptor(
        val id: String,
        val title: String,
        val subtitle: String,
        val playlistId: String
    )

    val FEATURED_CHARTS = listOf(
        ChartDescriptor(
            id = "top_songs_ph",
            title = "Top Songs - Philippines",
            subtitle = "Official Spotify Chart • Weekly",
            playlistId = "37i9dQZEVXbJVKdmjH0pON"
        ),
        ChartDescriptor(
            id = "top50_ph",
            title = "Top 50 - Philippines",
            subtitle = "Official Spotify Chart • Daily",
            playlistId = "37i9dQZEVXbNBz9cRCSFkY"
        ),
        ChartDescriptor(
            id = "top_songs_global",
            title = "Top Songs - Global",
            subtitle = "Official Spotify Chart • Weekly",
            playlistId = "37i9dQZEVXbNG2KDcFcKOF"
        ),
        ChartDescriptor(
            id = "top50_global",
            title = "Top 50 - Global",
            subtitle = "Official Spotify Chart • Daily",
            playlistId = "37i9dQZEVXbMDoHDwVN2tF"
        ),
        ChartDescriptor(
            id = "hot_hits_ph",
            title = "Hot Hits Philippines",
            subtitle = "Official Spotify Playlist",
            playlistId = "37i9dQZF1DXcZQSjptOQtk"
        )
    )

    fun getDescriptor(chartId: String): ChartDescriptor? =
        FEATURED_CHARTS.firstOrNull { it.id == chartId }

    fun cacheEnrichedChartTracks(chartId: String, tracks: List<TrackDto>) {
        if (tracks.isNotEmpty()) chartCache[chartId] = System.currentTimeMillis() to tracks
    }

    // chartId -> (timestampMs, tracks)
    private val chartCache = ConcurrentHashMap<String, Pair<Long, List<TrackDto>>>()
    private const val CACHE_TTL_MS = 30 * 60 * 1000L

    // "Artist|Title" -> iTunes metadata (misses cached as empty ITunesMeta)
    private val itunesCache = ConcurrentHashMap<String, ITunesMeta>()
    private val itunesLock = Semaphore(4)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    private data class ITunesMeta(val artworkUrl: String?, val durationSeconds: Long?)

    suspend fun getChartTracks(chartId: String, forceRefresh: Boolean = false): List<TrackDto> =
        withContext(Dispatchers.IO) {
            val cached = chartCache[chartId]
            if (!forceRefresh && cached != null &&
                System.currentTimeMillis() - cached.first < CACHE_TTL_MS
            ) {
                return@withContext cached.second
            }

            val descriptor = getDescriptor(chartId) ?: return@withContext emptyList()
            val embed = SpotifyEmbedFetcher.fetchPlaylist(descriptor.playlistId)
                ?: return@withContext emptyList()

            val tracks = embed.tracks
            if (tracks.isNotEmpty()) {
                chartCache[chartId] = Pair(System.currentTimeMillis(), tracks)
            }
            tracks
        }

    /** Official playlist cover art from Spotify (not the #1 track). */
    suspend fun getChartCover(chartId: String): String? {
        val descriptor = getDescriptor(chartId) ?: return null
        val embed = SpotifyEmbedFetcher.fetchPlaylist(descriptor.playlistId)
            ?: return null
        return embed.coverUrl
    }

    // ---------------- iTunes enrichment ----------------

    /** Fill in missing artwork/duration via iTunes Search (no key required). */
    suspend fun enrichWithITunes(tracks: List<TrackDto>): List<TrackDto> = coroutineScope {
        tracks.map { track ->
            async {
                if (track.thumbnailUrl != null && track.durationSeconds != null) {
                    return@async track
                }
                val meta = fetchITunesMeta(track)
                if (meta.artworkUrl != null || meta.durationSeconds != null) {
                    track.copy(
                        thumbnailUrl = track.thumbnailUrl ?: meta.artworkUrl,
                        durationSeconds = track.durationSeconds ?: meta.durationSeconds
                    )
                } else track
            }
        }.awaitAll()
    }

    private suspend fun fetchITunesMeta(track: TrackDto): ITunesMeta {
        val key = "${track.artist}|${track.title}".lowercase()
        itunesCache[key]?.let { return it }

        return itunesLock.withPermit {
            itunesCache[key]?.let { return@withPermit it }
            val query = java.net.URLEncoder.encode(
                "${track.artist} ${track.title}", "UTF-8"
            )
            val url = "https://itunes.apple.com/search?term=$query&entity=song&limit=8"
            val body = httpGet(url)
            val meta = if (body != null) {
                try {
                    val results = JSONObject(body).optJSONArray("results")
                    val expectedTitle = normalize(track.title)
                    val expectedArtist = normalize(track.artist.orEmpty())
                    val candidates = (0 until (results?.length() ?: 0)).mapNotNull { results?.optJSONObject(it) }
                    val best = candidates.maxByOrNull { candidate ->
                        val title = normalize(candidate.optString("trackName"))
                        val artist = normalize(candidate.optString("artistName"))
                        (if (title == expectedTitle) 100 else if (title.contains(expectedTitle) || expectedTitle.contains(title)) 40 else 0) +
                            (if (expectedArtist.isNotBlank() && (artist.contains(expectedArtist) || expectedArtist.contains(artist))) 25 else 0)
                    }?.takeIf { candidate ->
                        val title = normalize(candidate.optString("trackName"))
                        title == expectedTitle || (title.length > 3 && expectedTitle.length > 3 && (title.contains(expectedTitle) || expectedTitle.contains(title)))
                    }
                    if (best != null) {
                        val art100 = best.optString("artworkUrl100").takeIf { it.isNotBlank() }
                        val art = art100?.replace("100x100bb", "1000x1000bb")
                        val millis = best.optLong("trackTimeMillis", 0L)
                        ITunesMeta(
                            artworkUrl = art,
                            durationSeconds = if (millis > 0) millis / 1000 else null
                        )
                    } else ITunesMeta(null, null)
                } catch (e: Exception) {
                    ITunesMeta(null, null)
                }
            } else ITunesMeta(null, null)
            itunesCache[key] = meta
            meta
        }
    }

    private fun httpGet(url: String): String? {
        return try {
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body?.string() else null
            }
        } catch (e: Exception) {
            Log.w("SpotifyCharts", "GET failed: $url -> ${e.message}")
            null
        }
    }

    private fun normalize(value: String): String = value.lowercase()
        .replace(Regex("\\([^)]*\\)|\\[[^]]*\\]"), " ")
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
}
