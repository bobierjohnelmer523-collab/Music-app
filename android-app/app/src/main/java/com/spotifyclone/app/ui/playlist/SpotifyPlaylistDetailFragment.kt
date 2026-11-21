package com.spotifyclone.app.ui.playlist

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.spotifyclone.app.R
import com.spotifyclone.app.SpotifyApplication
import com.spotifyclone.app.databinding.FragmentPlaylistDetailBinding
import com.spotifyclone.app.model.TrackDto
import com.spotifyclone.app.spotify.SpotifyChartsFetcher
import com.spotifyclone.app.spotify.SpotifyImportStore
import com.spotifyclone.app.spotify.SpotifyRepository
import com.spotifyclone.app.ui.adapter.TrackAdapter
import com.spotifyclone.app.ui.dialog.TrackOptionsHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Detail view for Spotify-sourced playlists and official charts.
 * Tracks carry Spotify metadata; audio resolves via NewPipe at play time.
 */
class SpotifyPlaylistDetailFragment : Fragment() {

    private var _binding: FragmentPlaylistDetailBinding? = null
    private val binding get() = _binding!!

    private var mode: String = MODE_PLAYLIST
    private var sourceId: String = ""
    private var playlistTitle: String = ""
    private var playlistDescription: String? = null
    private var playlistCoverUrl: String? = null

    private val playlistTracks = mutableListOf<TrackDto>()
    private lateinit var trackAdapter: TrackAdapter

    companion object {
        private const val ARG_MODE = "arg_mode"
        private const val ARG_ID = "arg_id"
        private const val ARG_TITLE = "arg_title"
        private const val ARG_DESCRIPTION = "arg_description"
        private const val ARG_COVER_URL = "arg_cover_url"

        const val MODE_PLAYLIST = "playlist"
        const val MODE_CHART = "chart"

        fun forPlaylist(
            playlistId: String,
            title: String,
            description: String?,
            coverUrl: String?
        ): SpotifyPlaylistDetailFragment {
            val f = SpotifyPlaylistDetailFragment()
            f.arguments = Bundle().apply {
                putString(ARG_MODE, MODE_PLAYLIST)
                putString(ARG_ID, playlistId)
                putString(ARG_TITLE, title)
                putString(ARG_DESCRIPTION, description)
                putString(ARG_COVER_URL, coverUrl)
            }
            return f
        }

        fun forChart(chartId: String): SpotifyPlaylistDetailFragment {
            val descriptor = SpotifyChartsFetcher.getDescriptor(chartId)
            val f = SpotifyPlaylistDetailFragment()
            f.arguments = Bundle().apply {
                putString(ARG_MODE, MODE_CHART)
                putString(ARG_ID, chartId)
                putString(ARG_TITLE, descriptor?.title ?: chartId)
                putString(ARG_DESCRIPTION, descriptor?.subtitle)
                // Cover URL loaded separately in loadTracks() via SpotifyChartsFetcher.getChartCover()
            }
            return f
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            mode = it.getString(ARG_MODE, MODE_PLAYLIST)
            sourceId = it.getString(ARG_ID, "")
            playlistTitle = it.getString(ARG_TITLE, "")
            playlistDescription = it.getString(ARG_DESCRIPTION)
            playlistCoverUrl = it.getString(ARG_COVER_URL)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlaylistDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupUI()
        setupRecyclerView()
        observePlaybackUi()
        setupListeners()
        loadTracks()
    }

    private fun setupUI() {
        binding.textPlaylistTitle.text = playlistTitle
        if (!playlistDescription.isNullOrBlank()) {
            binding.textPlaylistDescription.text = playlistDescription
            binding.textPlaylistDescription.visibility = View.VISIBLE
        } else {
            binding.textPlaylistDescription.visibility = View.GONE
        }
        renderCover()

        // Spotify playlists are read-only here; keep only the Spotify options menu.
        binding.btnPlaylistOptions.visibility = View.VISIBLE
        binding.btnFindSongsToAdd.visibility = View.GONE
    }

    private fun renderCover() {
        val cover = playlistCoverUrl ?: playlistTracks.firstOrNull()?.thumbnailUrl
        if (!cover.isNullOrEmpty()) {
            binding.imgPlaylistCover.imageTintList = null
            Glide.with(this)
                .load(cover)
                .placeholder(R.drawable.ic_library)
                .error(R.drawable.ic_library)
                .centerCrop()
                .into(binding.imgPlaylistCover)
        } else {
            binding.imgPlaylistCover.setImageResource(R.drawable.ic_library)
            binding.imgPlaylistCover.imageTintList = null
        }
    }

    private fun setupRecyclerView() {
        trackAdapter = TrackAdapter(
            tracks = playlistTracks,
            onTrackClick = { _, position ->
                SpotifyApplication.instance.playbackManager.playPlaylistQueue(playlistTracks, position)
            },
            onMenuClick = { track ->
                TrackOptionsHelper.showTrackOptions(requireContext(), track)
            }
        )
        binding.recyclerPlaylistTracks.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = trackAdapter
        }
    }

    private fun observePlaybackUi() {
        val playback = SpotifyApplication.instance.playbackManager
        viewLifecycleOwner.lifecycleScope.launch {
            combine(playback.currentTrack, playback.isPlaying) { track, playing -> track to playing }
                .collectLatest { (track, playing) ->
                    updatePlayButton(track, playing)
                }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            playback.isShuffleEnabled.collectLatest { enabled ->
                binding.btnPlaylistShuffle.imageTintList = android.content.res.ColorStateList.valueOf(
                    requireContext().getColor(
                        if (enabled) R.color.spotify_green else R.color.spotify_light_grey
                    )
                )
            }
        }
    }

    private fun updatePlayButton(track: TrackDto?, playing: Boolean) {
        val isThisPlaylist = track != null && playlistTracks.any { playlistTrack ->
            (track.youtubeId.isNotBlank() && playlistTrack.youtubeId == track.youtubeId) ||
                (playlistTrack.title.equals(track.title, ignoreCase = true) &&
                    playlistTrack.artist.equals(track.artist, ignoreCase = true))
        }
        binding.imgPlayFabIcon.setImageResource(
            if (isThisPlaylist && playing) R.drawable.ic_pause else R.drawable.ic_play
        )
        binding.btnPlaylistPlayFab.contentDescription =
            if (isThisPlaylist && playing) "Pause playlist" else "Play playlist"
    }

    private fun setupListeners() {
        binding.btnBackPlaylistDetail.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        binding.btnPlaylistPlayFab.setOnClickListener {
            if (playlistTracks.isNotEmpty()) {
                val playback = SpotifyApplication.instance.playbackManager
                val current = playback.currentTrack.value
                val currentIsInPlaylist = current != null && playlistTracks.any { playlistTrack ->
                    (current.youtubeId.isNotBlank() && playlistTrack.youtubeId == current.youtubeId) ||
                        (playlistTrack.title.equals(current.title, ignoreCase = true) &&
                            playlistTrack.artist.equals(current.artist, ignoreCase = true))
                }
                if (currentIsInPlaylist) {
                    playback.togglePlayPause()
                } else {
                    val startIndex = if (playback.isShuffleEnabled.value) {
                        playlistTracks.indices.random()
                    } else {
                        0
                    }
                    playback.playPlaylistQueue(playlistTracks, startIndex)
                }
            } else {
                Toast.makeText(context, "Nothing to play yet", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnPlaylistShuffle.setOnClickListener {
            val playback = SpotifyApplication.instance.playbackManager
            playback.setShuffle(!playback.isShuffleEnabled.value)
        }

        binding.btnPlaylistDownloadAll.setOnClickListener {
            if (playlistTracks.isNotEmpty()) {
                Toast.makeText(
                    context,
                    "Downloading \"$playlistTitle\" (${playlistTracks.size} songs)...",
                    Toast.LENGTH_LONG
                ).show()
                for (track in playlistTracks) {
                    SpotifyApplication.instance.downloadHelper.downloadTrack(track)
                }
            } else {
                Toast.makeText(context, "No songs to download", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnPlaylistOptions.setOnClickListener { showOptionsMenu() }
    }

    private fun showOptionsMenu() {
        val options = mutableListOf("Download all songs")
        if (mode == MODE_PLAYLIST) options.add("Open in Spotify")
        if (SpotifyImportStore.contains(requireContext(), sourceId)) {
            options.add("Remove from Your Library")
        }

        AlertDialog.Builder(requireContext())
            .setTitle(playlistTitle)
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    "Download all songs" -> binding.btnPlaylistDownloadAll.performClick()
                    "Open in Spotify" -> {
                        val url = "https://open.spotify.com/playlist/$sourceId"
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }
                    "Remove from Your Library" -> {
                        SpotifyImportStore.remove(requireContext(), sourceId)
                        parentFragmentManager.popBackStack()
                    }
                }
            }
            .show()
    }

    private fun loadTracks() {
        binding.progressPlaylistDetail.visibility = View.VISIBLE
        binding.layoutPlaylistEmpty.visibility = View.GONE
        binding.recyclerPlaylistTracks.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            val content = withContext(Dispatchers.IO) {
                when (mode) {
                    MODE_CHART -> {
                        val tracks = SpotifyChartsFetcher.getChartTracks(sourceId)
                        val cover = SpotifyChartsFetcher.getChartCover(sourceId)
                        SpotifyRepository.PlaylistContent(
                            id = sourceId,
                            title = playlistTitle,
                            description = playlistDescription,
                            coverUrl = cover,
                            ownerName = null,
                            tracks = tracks,
                            viaApi = false
                        )
                    }
                    else -> SpotifyRepository.getPlaylistContent(sourceId)
                }
            }

            // Fill in metadata we didn't already have from the caller
            content?.let { c ->
                if (playlistTitle.isBlank()) playlistTitle = c.title
                if (playlistCoverUrl.isNullOrBlank()) playlistCoverUrl = c.coverUrl
                if (playlistDescription.isNullOrBlank()) playlistDescription = c.description
            }

            val tracks = content?.tracks ?: emptyList()

            playlistTracks.clear()
            playlistTracks.addAll(tracks)
            trackAdapter.updateTracks(playlistTracks)
            val playback = SpotifyApplication.instance.playbackManager
            updatePlayButton(playback.currentTrack.value, playback.isPlaying.value)

            val totalSeconds = playlistTracks.mapNotNull { it.durationSeconds }.sum()
            val minutes = totalSeconds / 60
            val count = playlistTracks.size

            val b = _binding
            if (b != null) {
                b.textPlaylistStats.text =
                    "Playlist • $count ${if (count == 1) "song" else "songs"} • $minutes min"

                setupUI()
                b.progressPlaylistDetail.visibility = View.GONE

                if (playlistTracks.isEmpty()) {
                    if (mode == MODE_PLAYLIST) {
                        b.textPlaylistDescription.text =
                            "Couldn't load this playlist — it may be private or unavailable."
                        b.textPlaylistDescription.visibility = View.VISIBLE
                    }
                    b.layoutPlaylistEmpty.visibility = View.VISIBLE
                } else {
                    b.layoutPlaylistEmpty.visibility = View.GONE
                    b.recyclerPlaylistTracks.visibility = View.VISIBLE
                }
            }

            if (tracks.any { it.thumbnailUrl.isNullOrBlank() || it.durationSeconds == null }) {
                viewLifecycleOwner.lifecycleScope.launch {
                    val enriched = withContext(Dispatchers.IO) {
                        SpotifyChartsFetcher.enrichWithITunes(tracks)
                    }
                    if (_binding != null && enriched.any { it.thumbnailUrl != null }) {
                        playlistTracks.clear()
                        playlistTracks.addAll(enriched)
                        trackAdapter.updateTracks(playlistTracks)
                        if (mode == MODE_CHART) {
                            SpotifyChartsFetcher.cacheEnrichedChartTracks(sourceId, enriched)
                        }
                    }
                }
            }

            // Pre-warm the top tracks in background so playback starts instantly
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                tracks.take(3).forEach { t ->
                    try {
                        val resolved = com.spotifyclone.app.spotify.SpotifyTrackResolver.resolve(t)
                        if (resolved != null && resolved.youtubeId.isNotBlank()) {
                            com.spotifyclone.app.extractor.NewPipeExtractorHelper.resolveStreamUrl(resolved.youtubeId)
                        }
                    } catch (_: Exception) {}
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
