package com.spotifyclone.app.ui.player

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.spotifyclone.app.R
import com.spotifyclone.app.SpotifyApplication
import com.spotifyclone.app.api.ApiClient
import com.spotifyclone.app.databinding.FragmentNowPlayingBinding
import com.spotifyclone.app.model.TrackDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NowPlayingFragment : DialogFragment() {

    private var _binding: FragmentNowPlayingBinding? = null
    private val binding get() = _binding!!

    private val playbackManager get() = SpotifyApplication.instance.playbackManager
    private var isUserTrackingSeekBar = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNowPlayingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupListeners()
        observePlaybackState()
    }

    private fun setupListeners() {
        binding.btnCollapse.setOnClickListener {
            dismiss()
        }

        binding.btnPlayPause.setOnClickListener {
            playbackManager.togglePlayPause()
        }

        binding.btnNext.setOnClickListener {
            playbackManager.playNext()
        }

        binding.btnPrev.setOnClickListener {
            playbackManager.playPrevious()
        }

        binding.btnDownloadTrack.setOnClickListener {
            playbackManager.currentTrack.value?.let { track ->
                binding.btnDownloadTrack.isEnabled = false
                SpotifyApplication.instance.downloadHelper.downloadTrack(track) { success ->
                    activity?.runOnUiThread {
                        binding.btnDownloadTrack.isEnabled = true
                        val message = if (success) "Downloaded for offline playback!" else "Failed to download track"
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        binding.btnAddToPlaylist.setOnClickListener {
            playbackManager.currentTrack.value?.let { track ->
                showAddToPlaylistDialog(track)
            }
        }

        binding.seekbarPlayback.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.textCurrentTime.text = formatTime(progress.toLong())
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUserTrackingSeekBar = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBar?.let {
                    playbackManager.seekTo(it.progress.toLong())
                }
                isUserTrackingSeekBar = false
            }
        })
    }

    private fun showAddToPlaylistDialog(track: TrackDto) {
        lifecycleScope.launch(Dispatchers.IO) {
            val localPlaylists = SpotifyApplication.instance.database.playlistDao().getAllPlaylists().first()
            if (localPlaylists.isEmpty()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "No playlists created yet. Create one in Your Library first.", Toast.LENGTH_LONG).show()
                }
                return@launch
            }

            val names = localPlaylists.map { it.title }.toTypedArray()
            withContext(Dispatchers.Main) {
                AlertDialog.Builder(requireContext())
                    .setTitle("Add to Playlist")
                    .setItems(names) { _, which ->
                        val selected = localPlaylists[which]
                        addTrackToSelectedPlaylist(selected.id, selected.remoteId, track)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    private fun addTrackToSelectedPlaylist(localId: Long, remoteId: Long?, track: TrackDto) {
        lifecycleScope.launch(Dispatchers.IO) {
            // Save in local Room
            SpotifyApplication.instance.database.trackDao().insert(
                com.spotifyclone.app.data.local.entities.LocalTrack(
                    playlistId = localId,
                    youtubeId = track.youtubeId,
                    title = track.title,
                    artist = track.artist,
                    durationSeconds = track.durationSeconds,
                    thumbnailUrl = track.thumbnailUrl
                )
            )

            // If synced with backend, push to backend API
            if (remoteId != null && ApiClient.isLoggedIn()) {
                try {
                    ApiClient.getApi().addTrackToPlaylist(remoteId, track)
                } catch (e: Exception) {
                    // Ignored or logged
                }
            }

            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Added to playlist!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun observePlaybackState() {
        lifecycleScope.launch {
            playbackManager.currentTrack.collectLatest { track ->
                if (track != null) {
                    binding.textSongTitle.text = track.title
                    binding.textSongArtist.text = track.artist ?: "Unknown Artist"

                    Glide.with(this@NowPlayingFragment)
                        .load(track.thumbnailUrl)
                        .placeholder(R.drawable.ic_music_note)
                        .into(binding.imageNowPlayingArt)
                }
            }
        }

        lifecycleScope.launch {
            playbackManager.isPlaying.collectLatest { isPlaying ->
                binding.btnPlayPause.setImageResource(
                    if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                )
            }
        }

        lifecycleScope.launch {
            playbackManager.durationMs.collectLatest { duration ->
                binding.seekbarPlayback.max = duration.toInt()
                binding.textTotalTime.text = formatTime(duration)
            }
        }

        lifecycleScope.launch {
            playbackManager.progressMs.collectLatest { progress ->
                if (!isUserTrackingSeekBar) {
                    binding.seekbarPlayback.progress = progress.toInt()
                    binding.textCurrentTime.text = formatTime(progress)
                }
            }
        }
    }

    private fun formatTime(millis: Long): String {
        val seconds = (millis / 1000) % 60
        val minutes = (millis / (1000 * 60)) % 60
        return String.format("%d:%02d", minutes, seconds)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
