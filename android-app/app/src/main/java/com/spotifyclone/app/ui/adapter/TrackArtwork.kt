package com.spotifyclone.app.ui.adapter

import android.graphics.drawable.Drawable
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.spotifyclone.app.R
import com.spotifyclone.app.model.TrackDto
import com.spotifyclone.app.spotify.SpotifyChartsFetcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

object TrackArtwork {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val officialArtworkCache = ConcurrentHashMap<String, String>()

    fun load(image: ImageView, track: TrackDto) {
        load(image, track.title, track.artist, track.thumbnailUrl, track.youtubeId)
    }

    fun load(image: ImageView, thumbnailUrl: String?, youtubeId: String?) {
        load(image, null, null, thumbnailUrl, youtubeId)
    }

    fun load(
        image: ImageView,
        title: String?,
        artist: String?,
        thumbnailUrl: String?,
        youtubeId: String?
    ) {
        // 1. If thumbnailUrl is already official album artwork (Spotify CDN, iTunes CDN, or custom non-youtube URL)
        if (!thumbnailUrl.isNullOrBlank() && !SpotifyChartsFetcher.isYouTubeThumbnail(thumbnailUrl)) {
            loadDirect(image, thumbnailUrl)
            return
        }

        // 2. Check if we have already resolved the official Spotify counterpart artwork for this title & artist
        val key = if (!title.isNullOrBlank()) "${title.trim()}|${artist?.trim().orEmpty()}".lowercase() else null
        if (key != null) {
            val cachedOfficial = officialArtworkCache[key]
            if (!cachedOfficial.isNullOrBlank()) {
                loadDirect(image, cachedOfficial)
                return
            }
        }

        // 3. Immediately display the available thumbnail/placeholder
        loadFallback(image, thumbnailUrl, youtubeId)

        // 4. If title is available, asynchronously resolve the official Spotify counterpart artwork in background
        if (key != null && !title.isNullOrBlank()) {
            image.tag = key
            scope.launch {
                try {
                    val official = SpotifyChartsFetcher.fetchOfficialArtwork(title, artist)
                    if (!official.isNullOrBlank()) {
                        officialArtworkCache[key] = official
                        withContext(Dispatchers.Main) {
                            if (image.tag == key) {
                                Glide.with(image)
                                    .load(official)
                                    .placeholder(image.drawable ?: image.context.getDrawable(R.drawable.ic_music_note))
                                    .error(R.drawable.ic_music_note)
                                    .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                                    .centerCrop()
                                    .into(image)
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private fun loadDirect(image: ImageView, url: String) {
        image.tag = null
        Glide.with(image)
            .load(url)
            .placeholder(R.drawable.ic_music_note)
            .error(R.drawable.ic_music_note)
            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
            .centerCrop()
            .into(image)
    }

    private fun loadFallback(image: ImageView, thumbnailUrl: String?, youtubeId: String?) {
        val candidates = buildList {
            thumbnailUrl?.takeIf(String::isNotBlank)?.let { add(it) }
            val id = youtubeId.orEmpty().substringAfter("v=", youtubeId.orEmpty()).substringBefore('&')
            val thumbId = thumbnailUrl.orEmpty().let { url ->
                Regex("/vi(?:_webp)?/([A-Za-z0-9_-]{11})/").find(url)?.groupValues?.getOrNull(1)
            }
            val videoId = id.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) } ?: thumbId
            if (videoId != null) {
                add("https://i.ytimg.com/vi/$videoId/hqdefault.jpg")
                add("https://i.ytimg.com/vi/$videoId/mqdefault.jpg")
            }
        }.distinct()

        val glide = Glide.with(image)
        var request: RequestBuilder<Drawable>? = null
        candidates.asReversed().forEach { url ->
            val next = glide.load(url)
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .centerCrop()
            val fallback = request
            request = if (fallback != null) next.error(fallback) else next
        }
        (request ?: glide.load(R.drawable.ic_music_note))
            .placeholder(R.drawable.ic_music_note)
            .error(R.drawable.ic_music_note)
            .centerCrop()
            .into(image)
    }
}
