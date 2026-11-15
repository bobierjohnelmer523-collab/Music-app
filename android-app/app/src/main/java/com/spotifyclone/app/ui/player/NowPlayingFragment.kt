package com.spotifyclone.app.ui.player

import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.switchmaterial.SwitchMaterial
import com.spotifyclone.app.R
import com.spotifyclone.app.SpotifyApplication
import com.spotifyclone.app.api.ApiClient
import com.spotifyclone.app.databinding.FragmentNowPlayingBinding
import com.spotifyclone.app.model.TrackDto
import com.spotifyclone.app.playback.RepeatMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLEncoder

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

        // 3 Dots options menu (Add to playlist, queue, share, download, song credits, favorite)
        binding.btnMoreMenu.setOnClickListener {
            playbackManager.currentTrack.value?.let { track ->
                com.spotifyclone.app.ui.dialog.TrackOptionsHelper.showTrackOptions(requireContext(), track)
            }
        }

        // Plus button: Add to playlist
        binding.btnAddToPlaylist.setOnClickListener {
            playbackManager.currentTrack.value?.let { track ->
                showAddToPlaylistDialog(track)
            }
        }

        // X button: Blacklist song and skip
        binding.btnBlacklistTrack.setOnClickListener {
            playbackManager.currentTrack.value?.let { track ->
                AlertDialog.Builder(requireContext())
                    .setTitle("Hide this song?")
                    .setMessage("We won't play \"${track.title}\" again automatically. You can still play it directly if you search for it.")
                    .setPositiveButton("Hide song") { _, _ ->
                        playbackManager.blacklistCurrentTrack {
                            Toast.makeText(context, "Song hidden and blacklisted", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }

        // Shuffle / Playback Options Bottom Sheet
        binding.btnShuffleMode.setOnClickListener {
            showPlaybackOptionsBottomSheet()
        }

        // Download button at bottom right
        binding.btnDownloadTrack.setOnClickListener {
            playbackManager.currentTrack.value?.let { track ->
                binding.btnDownloadTrack.isEnabled = false
                SpotifyApplication.instance.downloadHelper.downloadTrack(track) { success ->
                    activity?.runOnUiThread {
                        binding.btnDownloadTrack.isEnabled = true
                        if (success) {
                            binding.btnDownloadTrack.setColorFilter(
                                ContextCompat.getColor(requireContext(), R.color.spotify_green)
                            )
                            Toast.makeText(context, "Downloaded for offline playback!", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Download failed", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
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

    private fun shareCurrentSong() {
        val track = playbackManager.currentTrack.value ?: return
        val artist = track.artist ?: "Unknown Artist"
        val query = "${track.title} $artist"
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val spotifySearchUrl = "https://open.spotify.com/search/$encodedQuery"

        val shareText = "Listen to \"${track.title}\" by $artist on Spotify:\n$spotifySearchUrl"

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, track.title)
            putExtra(Intent.EXTRA_TEXT, shareText)
        }

        startActivity(Intent.createChooser(intent, "Share song to..."))
    }

    private fun showPlaybackOptionsBottomSheet() {
        val bottomSheet = BottomSheetDialog(requireContext())
        val view = layoutInflater.inflate(R.layout.dialog_playback_modes, null)
        bottomSheet.setContentView(view)

        val switchShuffle = view.findViewById<SwitchMaterial>(R.id.switch_smart_shuffle)
        val radioGroup = view.findViewById<RadioGroup>(R.id.radio_group_repeat)
        val radioOff = view.findViewById<RadioButton>(R.id.radio_repeat_off)
        val radioTwice = view.findViewById<RadioButton>(R.id.radio_repeat_twice)
        val radioContinuous = view.findViewById<RadioButton>(R.id.radio_repeat_continuous)
        val radioPlaylist = view.findViewById<RadioButton>(R.id.radio_repeat_playlist)

        // Set current states
        switchShuffle.isChecked = playbackManager.isShuffleEnabled.value
        when (playbackManager.repeatMode.value) {
            RepeatMode.OFF -> radioOff.isChecked = true
            RepeatMode.PLAY_TWICE -> radioTwice.isChecked = true
            RepeatMode.CONTINUOUS -> radioContinuous.isChecked = true
            RepeatMode.PLAYLIST -> radioPlaylist.isChecked = true
        }

        switchShuffle.setOnCheckedChangeListener { _, isChecked ->
            playbackManager.setShuffle(isChecked)
            updateShuffleButtonTint()
        }

        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.radio_repeat_twice -> RepeatMode.PLAY_TWICE
                R.id.radio_repeat_continuous -> RepeatMode.CONTINUOUS
                R.id.radio_repeat_playlist -> RepeatMode.PLAYLIST
                else -> RepeatMode.OFF
            }
            playbackManager.setRepeatMode(mode)
        }

        bottomSheet.show()
    }

    private fun updateShuffleButtonTint() {
        val isShuffle = playbackManager.isShuffleEnabled.value
        val green = ContextCompat.getColor(requireContext(), R.color.spotify_green)
        val white = ContextCompat.getColor(requireContext(), R.color.spotify_white)
        binding.btnShuffleMode.setColorFilter(if (isShuffle) green else white)
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

            if (remoteId != null && ApiClient.isLoggedIn()) {
                try {
                    ApiClient.getApi().addTrackToPlaylist(remoteId, track)
                } catch (e: Exception) {
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

                    // Check if already downloaded
                    checkIfDownloaded(track.youtubeId)
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
            playbackManager.isShuffleEnabled.collectLatest {
                updateShuffleButtonTint()
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

        lifecycleScope.launch {
            SpotifyApplication.instance.downloadHelper.downloadProgress.collectLatest { progressMap ->
                val track = playbackManager.currentTrack.value ?: return@collectLatest
                val progress = progressMap[track.youtubeId]
                if (progress != null && progress in 0..99) {
                    binding.btnDownloadTrack.visibility = View.GONE
                    binding.layoutDownloadProgress.visibility = View.VISIBLE
                    binding.progressCircleDownload.progress = progress
                    binding.textDownloadPercent.text = "$progress%"
                } else {
                    binding.layoutDownloadProgress.visibility = View.GONE
                    binding.btnDownloadTrack.visibility = View.VISIBLE
                    checkIfDownloaded(track.youtubeId)
                }
            }
        }
    }

    private fun checkIfDownloaded(youtubeId: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val isDownloaded = SpotifyApplication.instance.database.downloadedTrackDao().isDownloaded(youtubeId)
            withContext(Dispatchers.Main) {
                val green = ContextCompat.getColor(requireContext(), R.color.spotify_green)
                val white = ContextCompat.getColor(requireContext(), R.color.spotify_white)
                binding.btnDownloadTrack.setColorFilter(if (isDownloaded) green else white)
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
