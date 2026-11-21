package com.spotifyclone.app.ui.adapter

import android.graphics.drawable.Drawable
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.spotifyclone.app.R
import com.spotifyclone.app.model.TrackDto

object TrackArtwork {
    fun load(image: ImageView, track: TrackDto) {
        load(image, track.thumbnailUrl, track.youtubeId)
    }

    fun load(image: ImageView, thumbnailUrl: String?, youtubeId: String?) {
        val candidates = buildList {
            val id = youtubeId.orEmpty().substringAfter("v=", youtubeId.orEmpty()).substringBefore('&')
            val thumbId = thumbnailUrl.orEmpty().let { url ->
                Regex("/vi(?:_webp)?/([A-Za-z0-9_-]{11})/").find(url)?.groupValues?.getOrNull(1)
            }
            val videoId = id.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) } ?: thumbId
            if (videoId != null) {
                add("https://i.ytimg.com/vi/$videoId/maxresdefault.jpg")
                add("https://i.ytimg.com/vi/$videoId/sddefault.jpg")
            }
            thumbnailUrl?.takeIf(String::isNotBlank)?.let { add(it) }
            if (videoId != null) {
                add("https://i.ytimg.com/vi/$videoId/hqdefault.jpg")
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
