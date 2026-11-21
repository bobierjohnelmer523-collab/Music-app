package com.spotifyclone.app.ui.dialog

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.spotifyclone.app.R
import com.spotifyclone.app.SpotifyApplication
import com.spotifyclone.app.api.ApiClient
import com.spotifyclone.app.data.local.entities.LikedSong
import com.spotifyclone.app.data.local.entities.LocalTrack
import com.spotifyclone.app.model.TrackDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLEncoder

object TrackOptionsHelper {

    fun showTrackOptions(context: Context, track: TrackDto) {
        val bottomSheet = BottomSheetDialog(context)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_track_options, null)
        bottomSheet.setContentView(view)

        val imgArt = view.findViewById<ImageView>(R.id.img_sheet_track_art)
        val textTitle = view.findViewById<TextView>(R.id.text_sheet_track_title)
        val textArtist = view.findViewById<TextView>(R.id.text_sheet_track_artist)

        val layoutLike = view.findViewById<LinearLayout>(R.id.layout_option_like)
        val iconLike = view.findViewById<ImageView>(R.id.icon_option_like)
        val textLike = view.findViewById<TextView>(R.id.text_option_like)

        val layoutPlaylist = view.findViewById<LinearLayout>(R.id.layout_option_add_playlist)
        val layoutQueue = view.findViewById<LinearLayout>(R.id.layout_option_add_queue)
        val layoutDownload = view.findViewById<LinearLayout>(R.id.layout_option_download)
        val layoutShare = view.findViewById<LinearLayout>(R.id.layout_option_share)
        val layoutCredits = view.findViewById<LinearLayout>(R.id.layout_option_credits)

        textTitle.text = track.title
        textArtist.text = track.artist ?: "Unknown Artist"

        Glide.with(context)
            .load(track.thumbnailUrl)
            .placeholder(R.drawable.ic_music_note)
            .into(imgArt)

        val db = SpotifyApplication.instance.database
        val scope = CoroutineScope(Dispatchers.Main)

        // Check if currently liked
        scope.launch(Dispatchers.IO) {
            val isLiked = db.likedSongDao().isLiked(track.youtubeId)
            withContext(Dispatchers.Main) {
                if (isLiked) {
                    iconLike.setImageResource(R.drawable.ic_favorite)
                    iconLike.setColorFilter(ContextCompat.getColor(context, R.color.spotify_green))
                    textLike.text = "Remove from Liked Songs"
                } else {
                    iconLike.setImageResource(R.drawable.ic_favorite_border)
                    iconLike.setColorFilter(ContextCompat.getColor(context, R.color.spotify_white))
                    textLike.text = "Add to Liked Songs"
                }
            }
        }

        // 1. Toggle Liked Songs
        layoutLike.setOnClickListener {
            scope.launch(Dispatchers.IO) {
                val isLiked = db.likedSongDao().isLiked(track.youtubeId)
                if (isLiked) {
                    db.likedSongDao().deleteByYoutubeId(track.youtubeId)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Removed from Liked Songs", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    db.likedSongDao().insert(
                        LikedSong(
                            youtubeId = track.youtubeId,
                            title = track.title,
                            artist = track.artist ?: "Unknown Artist",
                            durationSeconds = track.durationSeconds,
                            thumbnailUrl = track.thumbnailUrl
                        )
                    )
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Added to Liked Songs ❤️", Toast.LENGTH_SHORT).show()
                    }
                }
                bottomSheet.dismiss()
            }
        }

        // 2. Add to Playlist
        layoutPlaylist.setOnClickListener {
            bottomSheet.dismiss()
            showAddToPlaylistDialog(context, track)
        }

        // 3. Add to Queue
        layoutQueue.setOnClickListener {
            SpotifyApplication.instance.playbackManager.addToQueue(track)
            Toast.makeText(context, "Added to queue", Toast.LENGTH_SHORT).show()
            bottomSheet.dismiss()
        }

        // 4. Download for Offline
        layoutDownload.setOnClickListener {
            SpotifyApplication.instance.downloadHelper.downloadTrack(track)
            Toast.makeText(context, "Download started for offline playback", Toast.LENGTH_SHORT).show()
            bottomSheet.dismiss()
        }

        // 5. Share Spotify Link
        layoutShare.setOnClickListener {
            bottomSheet.dismiss()
            shareTrack(context, track)
        }

        // 6. Song Credits
        layoutCredits.setOnClickListener {
            bottomSheet.dismiss()
            showCreditsDialog(context, track)
        }

        bottomSheet.show()
    }

    fun showAddToPlaylistDialog(context: Context, track: TrackDto) {
        val scope = CoroutineScope(Dispatchers.Main)
        scope.launch(Dispatchers.IO) {
            val localPlaylists = SpotifyApplication.instance.database.playlistDao().getAllPlaylists().first()
            if (localPlaylists.isEmpty()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "No playlists created yet. Create one in Your Library first.", Toast.LENGTH_LONG).show()
                }
                return@launch
            }

            val names = localPlaylists.map { it.title }.toTypedArray()
            withContext(Dispatchers.Main) {
                AlertDialog.Builder(context)
                    .setTitle("Add to Playlist")
                    .setItems(names) { _, which ->
                        val selected = localPlaylists[which]
                        addTrackToPlaylist(context, selected.id, selected.remoteId, track)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    private fun addTrackToPlaylist(context: Context, localId: Long, remoteId: Long?, track: TrackDto) {
        val scope = CoroutineScope(Dispatchers.IO)
        scope.launch {
            SpotifyApplication.instance.database.trackDao().insert(
                LocalTrack(
                    playlistId = localId,
                    youtubeId = track.youtubeId,
                    title = track.title,
                    artist = track.artist,
                    durationSeconds = track.durationSeconds,
                    thumbnailUrl = track.thumbnailUrl
                )
            )

            if (remoteId != null && ApiClient.isLoggedIn()) {
                try {
                    ApiClient.getApi().addTrackToPlaylist(remoteId, track)
                } catch (e: Exception) {
                    // Non-fatal sync error
                }
            }

            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Added to playlist!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun shareTrack(context: Context, track: TrackDto) {
        val artist = track.artist ?: "Unknown Artist"
        // Prefer the canonical Spotify track link when we have a Spotify id
        val shareUrl = if (!track.spotifyId.isNullOrBlank()) {
            "https://open.spotify.com/track/${track.spotifyId}"
        } else {
            val query = "${track.title} $artist"
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            "https://open.spotify.com/search/$encodedQuery"
        }

        val shareText = "Listen to \"${track.title}\" by $artist on Spotify:\n$shareUrl"

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, track.title)
            putExtra(Intent.EXTRA_TEXT, shareText)
        }
        context.startActivity(Intent.createChooser(intent, "Share song to..."))
    }

    private fun showCreditsDialog(context: Context, track: TrackDto) {
        val artist = track.artist ?: "Unknown Artist"
        val durationFormatted = track.durationSeconds?.let {
            val minutes = it / 60
            val seconds = it % 60
            String.format("%d:%02d", minutes, seconds)
        } ?: "3:45"

        val message = "• Title: ${track.title}\n" +
                "• Performed by: $artist\n" +
                "• Audio Format: High Quality 160 kbps M4A / AAC\n" +
                "• Duration: $durationFormatted\n" +
                "• Source: YouTube Music Official Catalog\n" +
                "• Offline Capability: Supported"

        CustomDialogHelper.showInfoDialog(context, "Song Credits", message)
    }
}
