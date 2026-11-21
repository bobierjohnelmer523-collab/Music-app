package com.spotifyclone.app.ui.player

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.switchmaterial.SwitchMaterial
import com.spotifyclone.app.R
import com.spotifyclone.app.SpotifyApplication
import com.spotifyclone.app.api.ApiClient
import com.spotifyclone.app.databinding.FragmentNowPlayingBinding
import com.spotifyclone.app.extractor.NewPipeExtractorHelper
import com.spotifyclone.app.model.TrackDto
import com.spotifyclone.app.playback.RepeatMode
import com.spotifyclone.app.ui.artist.ArtistDetailFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    private var lyricsJob: Job? = null
    private var artistLookupJob: Job? = null
    private var activeLyrics: TrackLyrics? = null
    private var activeLyricLine = -1
    private val lyricLineViews = mutableListOf<TextView>()
    private var artistChannelUrl: String? = null
    private var artistAvatarUrl: String? = null
    private var primaryArtistName: String? = null

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

        binding.root.doOnLayout { scrollContainer ->
            val targetHeight = (scrollContainer.height * 0.84f).toInt()
            if (targetHeight > 0 && binding.playerContentFrame.layoutParams.height != targetHeight) {
                binding.playerContentFrame.layoutParams = binding.playerContentFrame.layoutParams.apply {
                    height = targetHeight
                }
            }
        }
        setupListeners()
        observePlaybackState()
    }

    private fun setupListeners() {
        binding.btnCollapse.setOnClickListener {
            dismiss()
        }
        binding.cardLyricsArtist.setOnClickListener { openArtistProfile() }
        binding.cardLyricsPreview.setOnClickListener { openFullscreenLyrics() }

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
                com.spotifyclone.app.ui.dialog.CustomDialogHelper.showConfirmDialog(
                    context = requireContext(),
                    title = "Hide this song?",
                    message = "We won't play \"${track.title}\" again automatically. You can still play it directly if you search for it.",
                    confirmText = "Hide Song",
                    isDestructive = true
                ) {
                    playbackManager.blacklistCurrentTrack {
                        Toast.makeText(context, "Song hidden and blacklisted", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        // Shuffle / Playback Options Bottom Sheet
        binding.btnShuffleMode.setOnClickListener {
            playbackManager.setShuffle(!playbackManager.isShuffleEnabled.value)
            updateShuffleButtonTint()
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
        viewLifecycleOwner.lifecycleScope.launch {
            playbackManager.currentTrack.collectLatest { track ->
                if (track != null) {
                    binding.textSongTitle.text = track.title
                    binding.textSongArtist.text = track.artist ?: "Unknown Artist"

                    com.spotifyclone.app.ui.adapter.TrackArtwork.load(binding.imageNowPlayingArt, track)

                    // Check if already downloaded
                    checkIfDownloaded(track.youtubeId)
                    loadLyrics(track)
                    loadArtistProfile(track)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            playbackManager.isPlaying.collectLatest { isPlaying ->
                binding.btnPlayPause.setImageResource(
                    if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                )
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            playbackManager.isShuffleEnabled.collectLatest {
                updateShuffleButtonTint()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            playbackManager.durationMs.collectLatest { duration ->
                binding.seekbarPlayback.max = duration.toInt()
                val remaining = (duration - playbackManager.progressMs.value).coerceAtLeast(0L)
                binding.textTotalTime.text = "-${formatTime(remaining)}"
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            playbackManager.progressMs.collectLatest { progress ->
                if (!isUserTrackingSeekBar) {
                    binding.seekbarPlayback.progress = progress.toInt()
                    binding.textCurrentTime.text = formatTime(progress)
                }
                val remaining = (playbackManager.durationMs.value - progress).coerceAtLeast(0L)
                binding.textTotalTime.text = "-${formatTime(remaining)}"
                updateActiveLyric(progress)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
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
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val isDownloaded = SpotifyApplication.instance.database.downloadedTrackDao().isDownloaded(youtubeId)
            withContext(Dispatchers.Main) {
                val green = ContextCompat.getColor(requireContext(), R.color.spotify_green)
                val white = ContextCompat.getColor(requireContext(), R.color.spotify_white)
                binding.btnDownloadTrack.setColorFilter(if (isDownloaded) green else white)
            }
        }
    }

    private fun loadLyrics(track: TrackDto) {
        lyricsJob?.cancel()
        activeLyrics = null
        activeLyricLine = -1
        binding.layoutLyricsLines.removeAllViews()
        lyricLineViews.clear()
        binding.textLyricsStatus.text = "Loading lyrics…"
        lyricsJob = viewLifecycleOwner.lifecycleScope.launch {
            val lyrics = LyricsRepository.fetch(track.title, track.artist.orEmpty(), track.durationSeconds)
            if (playbackManager.currentTrack.value?.youtubeId != track.youtubeId) return@launch
            activeLyrics = lyrics
            if (lyrics == null) {
                binding.textLyricsStatus.text = "Lyrics aren’t available for this song yet."
                return@launch
            }
            binding.textLyricsStatus.text = if (lyrics.isSynced) {
                "Synced lyrics"
            } else {
                "Lyrics found · timing unavailable"
            }
            updateActiveLyric(playbackManager.progressMs.value, force = true)
        }
    }

    private fun updateActiveLyric(positionMs: Long, force: Boolean = false) {
        val lyrics = activeLyrics ?: return
        if (!lyrics.isSynced) {
            if (force || lyricLineViews.size != lyrics.lines.size) {
                activeLyricLine = -1
                renderLyricPreview(lyrics.lines, -1)
            }
            return
        }
        // Advance lyric sync by 0.3s (300ms) to ensure smooth lead-in and eliminate delay
        val adjustedPositionMs = positionMs + 300L
        val lineIndex = lyrics.lines.indexOfLast { it.timeMs <= adjustedPositionMs }
        if (!force && lineIndex == activeLyricLine) return
        activeLyricLine = lineIndex
        if (force || lyricLineViews.size != lyrics.lines.size) {
            renderLyricPreview(lyrics.lines, lineIndex)
        } else {
            updateLyricLineStyles(lineIndex)
        }
        if (lineIndex >= 0) {
            binding.scrollLyricsPreview.post {
                val activeLine = lyricLineViews.getOrNull(lineIndex) ?: return@post
                val targetY = (activeLine.top - binding.scrollLyricsPreview.height / 3).coerceAtLeast(0)
                binding.scrollLyricsPreview.smoothScrollTo(0, targetY)
            }
        }
    }

    private fun renderLyricPreview(lines: List<TimedLyricLine>, currentIndex: Int) {
        val container = binding.layoutLyricsLines
        container.removeAllViews()
        lyricLineViews.clear()
        if (lines.isEmpty()) return

        val verticalPadding = (7 * resources.displayMetrics.density).toInt()
        for (lyric in lines) {
            val line = TextView(requireContext()).apply {
                text = lyric.text
                maxLines = 3
                textSize = 17f
                setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD))
                setPadding(0, verticalPadding, 0, verticalPadding)
            }
            container.addView(line)
            lyricLineViews.add(line)
        }
        updateLyricLineStyles(currentIndex)
    }

    private fun updateLyricLineStyles(currentIndex: Int) {
        lyricLineViews.forEachIndexed { index, line ->
            val active = index == currentIndex
            line.setTextColor(if (active) Color.WHITE else Color.BLACK)
            line.alpha = if (active || currentIndex < 0) 1f else 0.72f
            line.textSize = if (active) 18f else 17f
        }
    }

    private fun openFullscreenLyrics() {
        val track = playbackManager.currentTrack.value ?: return
        LyricsFullscreenDialogFragment.forTrack(track, activeLyrics)
            .show(parentFragmentManager, "FullscreenLyrics")
    }

    private fun loadArtistProfile(track: TrackDto) {
        val fullArtist = track.artist.orEmpty().trim()
        binding.textLyricsArtist.text = fullArtist.ifBlank { "Unknown Artist" }
        val featureCredit = Regex("\\s+(?:feat(?:uring)?\\.?|ft\\.?)\\s+", RegexOption.IGNORE_CASE)
        val mainArtistCredit = fullArtist.substringBefore(',')
        primaryArtistName = featureCredit.find(mainArtistCredit)
            ?.range
            ?.first
            ?.let(mainArtistCredit::substring)
            ?.trim()
            ?: mainArtistCredit.trim()
        artistChannelUrl = null
        artistAvatarUrl = null
        binding.imageLyricsArtist.setImageResource(R.drawable.ic_music_note)
        artistLookupJob?.cancel()
        val artistQuery = primaryArtistName.orEmpty()
        if (artistQuery.isBlank()) return

        artistLookupJob = viewLifecycleOwner.lifecycleScope.launch {
            val match = withContext(Dispatchers.IO) {
                NewPipeExtractorHelper.searchYouTubeMusicArtists(artistQuery)
                    .getOrDefault(emptyList())
                    .let { artists ->
                        artists.firstOrNull { it.name.equals(artistQuery, ignoreCase = true) }
                            ?: artists.firstOrNull { it.name.contains(artistQuery, ignoreCase = true) }
                    }
            }
            if (playbackManager.currentTrack.value?.youtubeId != track.youtubeId) return@launch
            if (match != null) {
                artistChannelUrl = match.channelUrl
                artistAvatarUrl = match.avatarUrl
                Glide.with(this@NowPlayingFragment)
                    .load(match.avatarUrl)
                    .placeholder(R.drawable.ic_music_note)
                    .error(R.drawable.ic_music_note)
                    .circleCrop()
                    .into(binding.imageLyricsArtist)
            }
        }
    }

    private fun openArtistProfile() {
        val artistName = primaryArtistName.orEmpty()
        if (artistName.isBlank()) return
        viewLifecycleOwner.lifecycleScope.launch {
            var channel = artistChannelUrl
            if (channel.isNullOrBlank()) {
                channel = withContext(Dispatchers.IO) {
                    NewPipeExtractorHelper.searchYouTubeMusicArtists(artistName)
                        .getOrDefault(emptyList())
                        .firstOrNull { it.name.equals(artistName, ignoreCase = true) }
                        ?.channelUrl
                }
            }
            val artistFragment = ArtistDetailFragment.forArtist(
                artistName = artistName,
                avatarUrl = artistAvatarUrl,
                channelUrl = channel
            )
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, artistFragment)
                .addToBackStack("artist_profile")
                .commit()
            dismiss()
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
