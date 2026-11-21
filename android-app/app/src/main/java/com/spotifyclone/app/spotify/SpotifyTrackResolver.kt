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
        if (track.youtubeId.isNotBlank()) return track

        val cacheKey = "${track.spotifyId ?: ""}|${track.title}|${track.artist ?: ""}"
        resolutionCache[cacheKey]?.let { return it }

        val query = listOfNotNull(track.title, track.artist)
            .joinToString(" ")
            .trim()
        if (query.isEmpty()) return null

        val result = NewPipeExtractorHelper.searchYouTubeMusic(query)
        val match = result.getOrNull()?.firstOrNull() ?: return null

        val resolved = track.copy(
            youtubeId = match.youtubeId,
            // Keep Spotify artwork (better quality); only fill gaps
            thumbnailUrl = track.thumbnailUrl ?: match.thumbnailUrl,
            durationSeconds = track.durationSeconds ?: match.durationSeconds
        )
        resolutionCache[cacheKey] = resolved
        return resolved
    }
}
