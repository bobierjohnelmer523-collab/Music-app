package com.spotifyclone.app.spotify

import com.spotifyclone.app.extractor.NewPipeExtractorHelper
import com.spotifyclone.app.model.TrackDto

/**
 * Bridges Spotify catalog metadata to playable YouTube Music audio.
 *
 * A Spotify track has no audio of its own in this app — when it is about to
 * play (or be downloaded), we search YouTube Music via NewPipe for the best
 * "title artist" match and cache the resolved youtubeId on the TrackDto.
 */
object SpotifyTrackResolver {

    // "spotifyId|title|artist" -> resolved TrackDto (with youtubeId filled)
    private val resolutionCache = java.util.concurrent.ConcurrentHashMap<String, TrackDto>()

    /**
     * Returns a TrackDto with a real youtubeId, or null if no YouTube Music
     * match could be found. Safe to call for already-resolved tracks.
     */
    suspend fun resolve(track: TrackDto): TrackDto? {
        if (track.youtubeId.isNotBlank()) {
            // Even if already resolved, make sure it has official album artwork
            if (track.thumbnailUrl == null || SpotifyChartsFetcher.isYouTubeThumbnail(track.thumbnailUrl)) {
                val official = SpotifyChartsFetcher.fetchOfficialArtwork(track.title, track.artist)
                if (official != null) return track.copy(thumbnailUrl = official)
            }
            return track
        }

        val cacheKey = "${track.spotifyId ?: ""}|${track.title}|${track.artist ?: ""}"
        resolutionCache[cacheKey]?.let { return it }

        val title = track.title.trim()
        val artist = track.artist?.trim().orEmpty()
        val cleanTitle = title
            .replace(Regex("(?i)\\s*[\\[(](?:feat|ft|with)[^\\])]*[\\])]"), "")
            .replace(Regex("(?i)\\s*-\\s*(?:feat|ft|with)\\b.*"), "")
            .trim()
        val cleanArtist = artist
            .replace(Regex("(?i)\\s*[\\[(](?:feat|ft|with)[^\\])]*[\\])]"), "")
            .split(Regex("[,&/]|(?i)\\s+feat\\.?\\s+|\\s+ft\\.?\\s+"))
            .firstOrNull()
            ?.trim()
            ?: artist

        // Query candidates in priority order
        val queries = listOfNotNull(
            "$title $artist".trim().takeIf { it.isNotBlank() },
            if (cleanTitle != title || cleanArtist != artist) "$cleanTitle $cleanArtist".trim().takeIf { it.isNotBlank() } else null,
            cleanTitle.takeIf { it.isNotBlank() }
        ).distinct()

        var match: TrackDto? = null

        // 1. Try YouTube Music search
        for (q in queries) {
            val result = NewPipeExtractorHelper.searchYouTubeMusic(q)
            val first = result.getOrNull()?.firstOrNull()
            if (first != null && first.youtubeId.isNotBlank()) {
                match = first
                break
            }
        }

        // 2. Fallback to general YouTube search if YouTube Music had no results
        if (match == null) {
            for (q in queries) {
                val result = NewPipeExtractorHelper.fetchSearchTracks("$q audio", 5)
                val first = result.getOrNull()?.firstOrNull()
                if (first != null && first.youtubeId.isNotBlank()) {
                    match = first
                    break
                }
            }
        }

        if (match == null) return null

        // Keep official Spotify / iTunes artwork; only fallback to match thumbnail if needed
        val officialArt = track.thumbnailUrl?.takeIf { !SpotifyChartsFetcher.isYouTubeThumbnail(it) }
            ?: SpotifyChartsFetcher.fetchOfficialArtwork(track.title, track.artist)
            ?: track.thumbnailUrl
            ?: match.thumbnailUrl

        val resolved = track.copy(
            youtubeId = match.youtubeId,
            thumbnailUrl = officialArt,
            durationSeconds = track.durationSeconds ?: match.durationSeconds
        )
        resolutionCache[cacheKey] = resolved
        return resolved
    }
}
