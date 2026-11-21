package com.spotifyclone.app.ui.album

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.spotifyclone.app.R
import com.spotifyclone.app.SpotifyApplication
import com.spotifyclone.app.databinding.FragmentAlbumDetailBinding
import com.spotifyclone.app.databinding.ItemTrackBinding
import com.spotifyclone.app.extractor.NewPipeExtractorHelper
import com.spotifyclone.app.model.AlbumDto
import com.spotifyclone.app.model.TrackDto
import com.spotifyclone.app.ui.artist.ArtistDetailFragment
import com.spotifyclone.app.ui.dialog.TrackOptionsHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AlbumDetailFragment : Fragment() {

    private var _binding: FragmentAlbumDetailBinding? = null
    private val binding get() = _binding!!

    private var albumId: String? = null
    private var albumTitle: String = ""
    private var artistName: String? = null
    private var artistChannelUrl: String? = null
    private var expectedTrackCount: Int? = null
    private var coverUrl: String? = null

    private val albumTracks = mutableListOf<TrackDto>()
    companion object {
        private const val ARG_ALBUM_ID = "arg_album_id"
        private const val ARG_ALBUM_TITLE = "arg_album_title"
        private const val ARG_ARTIST_NAME = "arg_artist_name"
        private const val ARG_CHANNEL_URL = "arg_channel_url"
        private const val ARG_TRACK_COUNT = "arg_track_count"
        private const val ARG_COVER_URL = "arg_cover_url"

        fun forAlbum(album: AlbumDto, fallbackArtistName: String? = null): AlbumDetailFragment {
            val f = AlbumDetailFragment()
            f.arguments = Bundle().apply {
                putString(ARG_ALBUM_ID, album.id)
                putString(ARG_ALBUM_TITLE, album.name)
                putString(ARG_ARTIST_NAME, album.artistName ?: fallbackArtistName)
                putString(ARG_CHANNEL_URL, album.channelUrl)
                putInt(ARG_TRACK_COUNT, album.trackCount ?: -1)
                putString(ARG_COVER_URL, album.coverUrl)
            }
            return f
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            albumId = it.getString(ARG_ALBUM_ID)
            albumTitle = it.getString(ARG_ALBUM_TITLE, "")
            artistName = it.getString(ARG_ARTIST_NAME)
            artistChannelUrl = it.getString(ARG_CHANNEL_URL)
            expectedTrackCount = it.getInt(ARG_TRACK_COUNT, -1).takeIf { count -> count > 0 }
            coverUrl = it.getString(ARG_COVER_URL)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAlbumDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupUI()
        setupListeners()
        observePlaybackControls()
        loadAlbumTracks()
    }

    private fun setupUI() {
        binding.textAlbumTitle.text = albumTitle

        if (!artistName.isNullOrBlank()) {
            binding.textAlbumArtist.text = artistName
            binding.textAlbumArtist.visibility = View.VISIBLE
        } else {
            binding.textAlbumArtist.visibility = View.GONE
        }

        binding.textAlbumStats.text = releaseTypeLabel(expectedTrackCount)

        renderCoverImage()
    }

    private fun renderCoverImage() {
        if (!coverUrl.isNullOrEmpty()) {
            Glide.with(this)
                .load(coverUrl)
                .placeholder(R.drawable.ic_library)
                .error(R.drawable.ic_library)
                .centerCrop()
                .into(binding.imgAlbumCover)
        } else {
            binding.imgAlbumCover.setImageResource(R.drawable.ic_library)
        }
    }

    private fun setupListeners() {
        binding.btnBackAlbumDetail.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        binding.textAlbumArtist.setOnClickListener {
            val name = artistName
            if (!name.isNullOrBlank()) {
                val fragment = ArtistDetailFragment.forArtist(name, channelUrl = artistChannelUrl)
                parentFragmentManager.beginTransaction()
                    .replace(R.id.fragment_container, fragment)
                    .addToBackStack(null)
                    .commit()
            }
        }

        binding.btnAlbumPlayFab.setOnClickListener {
            if (albumTracks.isNotEmpty()) {
                val playback = SpotifyApplication.instance.playbackManager
                val currentTrackId = playback.currentTrack.value?.youtubeId
                if (!currentTrackId.isNullOrBlank() && albumTracks.any { it.youtubeId == currentTrackId }) {
                    playback.togglePlayPause()
                } else {
                    val startIndex = if (playback.isShuffleEnabled.value) {
                        albumTracks.indices.random()
                    } else {
                        0
                    }
                    playback.playPlaylistQueue(albumTracks, startIndex)
                }
            } else {
                Toast.makeText(context, "No songs to play", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnAlbumShuffle.setOnClickListener {
            val playback = SpotifyApplication.instance.playbackManager
            playback.setShuffle(!playback.isShuffleEnabled.value)
        }

        binding.btnAlbumDownloadAll.setOnClickListener {
            if (albumTracks.isEmpty()) {
                Toast.makeText(context, "Album tracks are still loading", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Toast.makeText(
                context,
                "Downloading ${albumTracks.size} album songs for offline listening…",
                Toast.LENGTH_LONG
            ).show()
            albumTracks.forEach { track ->
                SpotifyApplication.instance.downloadHelper.downloadTrack(track)
            }
        }
    }

    private fun observePlaybackControls() {
        val playback = SpotifyApplication.instance.playbackManager
        viewLifecycleOwner.lifecycleScope.launch {
            combine(playback.currentTrack, playback.isPlaying) { track, playing -> track to playing }
                .collectLatest { (track, playing) ->
                    val isThisAlbum = track != null && track.youtubeId.isNotBlank() &&
                        albumTracks.any { it.youtubeId == track.youtubeId }
                    binding.btnAlbumPlayFab.setImageResource(
                        if (isThisAlbum && playing) R.drawable.ic_pause else R.drawable.ic_play
                    )
                    binding.btnAlbumPlayFab.contentDescription =
                        if (isThisAlbum && playing) "Pause album" else "Play album"
                }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            playback.isShuffleEnabled.collectLatest { enabled ->
                binding.btnAlbumShuffle.backgroundTintList = android.content.res.ColorStateList.valueOf(
                    requireContext().getColor(
                        if (enabled) R.color.spotify_green else R.color.spotify_card_grey
                    )
                )
                binding.btnAlbumShuffle.imageTintList = android.content.res.ColorStateList.valueOf(
                    requireContext().getColor(
                        if (enabled) R.color.spotify_black else R.color.spotify_white
                    )
                )
            }
        }
    }

    private fun loadAlbumTracks() {
        binding.progressAlbumDetail.visibility = View.VISIBLE
        binding.layoutAlbumEmpty.visibility = View.GONE
        binding.recyclerAlbumTracks.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                NewPipeExtractorHelper.fetchAlbumTracks(
                    albumId,
                    albumTitle,
                    artistName,
                    expectedTrackCount
                )
            }

            val b = _binding ?: return@launch
            b.progressAlbumDetail.visibility = View.GONE

            if (result.isSuccess) {
                val tracks = result.getOrDefault(emptyList())
                if (tracks.isNotEmpty()) {
                    albumTracks.clear()
                    albumTracks.addAll(tracks)
                    val playback = SpotifyApplication.instance.playbackManager
                    val current = playback.currentTrack.value
                    val currentIsThisAlbum = current != null && current.youtubeId.isNotBlank() &&
                        albumTracks.any { it.youtubeId == current.youtubeId }
                    b.btnAlbumPlayFab.setImageResource(
                        if (currentIsThisAlbum && playback.isPlaying.value) R.drawable.ic_pause else R.drawable.ic_play
                    )
                    b.recyclerAlbumTracks.removeAllViews()
                    tracks.forEachIndexed { position, track ->
                        val row = ItemTrackBinding.inflate(layoutInflater, b.recyclerAlbumTracks, false)
                        row.textTrackNumber.text = (position + 1).toString()
                        row.textTrackNumber.visibility = View.VISIBLE
                        row.textTrackTitle.text = track.title
                        row.textTrackArtist.text = "${track.artist ?: ""} • ${formatDuration(track.durationSeconds ?: 0)}"
                        Glide.with(this@AlbumDetailFragment)
                            .load(track.thumbnailUrl)
                            .placeholder(R.drawable.ic_music_note)
                            .error(R.drawable.ic_music_note)
                            .transition(DrawableTransitionOptions.withCrossFade())
                            .into(row.imageTrackThumbnail)
                        row.root.setOnClickListener {
                            SpotifyApplication.instance.playbackManager.playPlaylistQueue(albumTracks, position)
                        }
                        row.btnTrackMenu.setOnClickListener {
                            TrackOptionsHelper.showTrackOptions(requireContext(), track)
                        }
                        b.recyclerAlbumTracks.addView(row.root)
                    }

                    b.textAlbumStats.text = "${releaseTypeLabel(expectedTrackCount ?: tracks.size)} • ${tracks.size} ${if (tracks.size == 1) "song" else "songs"}"

                    if (coverUrl.isNullOrEmpty()) {
                        val firstThumb = tracks.firstOrNull()?.thumbnailUrl
                        if (!firstThumb.isNullOrEmpty()) {
                            coverUrl = firstThumb
                            renderCoverImage()
                        }
                    }

                    b.recyclerAlbumTracks.visibility = View.VISIBLE
                } else {
                    b.layoutAlbumEmpty.visibility = View.VISIBLE
                }
            } else {
                Toast.makeText(context, "Failed to load album songs", Toast.LENGTH_SHORT).show()
                b.layoutAlbumEmpty.visibility = View.VISIBLE
            }
        }
    }

    private fun releaseTypeLabel(trackCount: Int?): String =
        if (trackCount != null && trackCount > 6) "Album" else "Single & EP"

    private fun formatDuration(seconds: Long): String =
        String.format("%d:%02d", seconds / 60, seconds % 60)

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
