package com.spotifyclone.app.ui.artist

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.spotifyclone.app.R
import com.spotifyclone.app.SpotifyApplication
import com.spotifyclone.app.databinding.FragmentArtistDetailBinding
import com.spotifyclone.app.extractor.NewPipeExtractorHelper
import com.spotifyclone.app.extractor.SourceBrokenException
import com.spotifyclone.app.model.AlbumDto
import com.spotifyclone.app.model.ArtistChannel
import com.spotifyclone.app.model.ArtistDto
import com.spotifyclone.app.model.TrackDto
import com.spotifyclone.app.ui.adapter.AlbumAdapter
import com.spotifyclone.app.ui.adapter.ArtistAdapter
import com.spotifyclone.app.ui.adapter.TrackAdapter
import com.spotifyclone.app.ui.album.AlbumDetailFragment
import com.spotifyclone.app.ui.dialog.TrackOptionsHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class ArtistDetailFragment : Fragment() {

    private var _binding: FragmentArtistDetailBinding? = null
    private val binding get() = _binding!!

    private lateinit var trackAdapter: TrackAdapter
    private lateinit var albumAdapter: AlbumAdapter
    private lateinit var singlesAdapter: AlbumAdapter
    private lateinit var fansAlsoLikeAdapter: ArtistAdapter

    private val artistTracks = mutableListOf<TrackDto>()
    private val artistAlbums = mutableListOf<AlbumDto>()
    private val artistSingles = mutableListOf<AlbumDto>()
    private val relatedArtists = mutableListOf<ArtistDto>()

    private var artistName: String = ""
    private var artistAvatarUrl: String? = null
    private var spotifyArtistId: String? = null
    private var artistChannelUrl: String? = null
    private var isExpanded = false
    private val allArtistTracks = mutableListOf<TrackDto>()
    private var activeArtistQueueIds: Set<String> = emptySet()
    private var isPreparingArtistQueue = false

    companion object {
        private const val ARG_ARTIST_NAME = "arg_artist_name"
        private const val ARG_ARTIST_AVATAR = "arg_artist_avatar"
        private const val ARG_SPOTIFY_ID = "arg_spotify_id"
        private const val ARG_CHANNEL_URL = "arg_channel_url"

        fun forArtist(artistName: String, avatarUrl: String? = null, spotifyId: String? = null, channelUrl: String? = null): ArtistDetailFragment {
            val f = ArtistDetailFragment()
            f.arguments = Bundle().apply {
                putString(ARG_ARTIST_NAME, artistName)
                putString(ARG_ARTIST_AVATAR, avatarUrl)
                putString(ARG_SPOTIFY_ID, spotifyId)
                putString(ARG_CHANNEL_URL, channelUrl)
            }
            return f
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            artistName = it.getString(ARG_ARTIST_NAME, "")
            artistAvatarUrl = it.getString(ARG_ARTIST_AVATAR)
            spotifyArtistId = it.getString(ARG_SPOTIFY_ID)
            artistChannelUrl = it.getString(ARG_CHANNEL_URL)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentArtistDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        observePlaybackControls()
        setupListeners()
        loadArtistChannel()
    }

    private fun setupRecyclerView() {
        trackAdapter = TrackAdapter(
            tracks = artistTracks,
            onTrackClick = { _, position ->
                val tracks = artistTracks.toList()
                activeArtistQueueIds = tracks.mapTo(mutableSetOf()) { it.youtubeId }
                SpotifyApplication.instance.playbackManager.playPlaylistQueue(tracks, position)
            },
            onMenuClick = { track ->
                TrackOptionsHelper.showTrackOptions(requireContext(), track)
            }
        )
        binding.recyclerArtistTracks.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = trackAdapter
        }

        albumAdapter = AlbumAdapter(
            albums = artistAlbums,
            onAlbumClick = { album ->
                val fragment = AlbumDetailFragment.forAlbum(album, artistName)
                parentFragmentManager.beginTransaction()
                    .replace(R.id.fragment_container, fragment)
                    .addToBackStack(null)
                    .commit()
            }
        )
        binding.recyclerAlbums.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = albumAdapter
        }

        singlesAdapter = AlbumAdapter(
            albums = artistSingles,
            onAlbumClick = { single ->
                val fragment = AlbumDetailFragment.forAlbum(single, artistName)
                parentFragmentManager.beginTransaction()
                    .replace(R.id.fragment_container, fragment)
                    .addToBackStack(null)
                    .commit()
            }
        )
        binding.recyclerSingles.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = singlesAdapter
        }

        fansAlsoLikeAdapter = ArtistAdapter(
            artists = relatedArtists,
            onArtistClick = { artist ->
                val fragment = forArtist(artist.name, artist.avatarUrl, null, artist.channelUrl)
                parentFragmentManager.beginTransaction()
                    .replace(R.id.fragment_container, fragment)
                    .addToBackStack(null)
                    .commit()
            }
        )
        binding.recyclerFansAlsoLike.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = fansAlsoLikeAdapter
        }
    }

    private fun setupListeners() {
        binding.btnBackArtistDetail.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        binding.btnArtistPlayFab.setOnClickListener {
            if (artistTracks.isNotEmpty()) {
                val playback = SpotifyApplication.instance.playbackManager
                val currentIsArtistTrack = playback.currentTrack.value?.youtubeId?.let { id ->
                    id in activeArtistQueueIds || allArtistTracks.any { it.youtubeId == id }
                } == true
                if (currentIsArtistTrack) {
                    playback.togglePlayPause()
                } else {
                    playArtistDiscography()
                }
            } else {
                Toast.makeText(context, "No songs to play", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnArtistShuffle.setOnClickListener {
            val playback = SpotifyApplication.instance.playbackManager
            playback.setShuffle(!playback.isShuffleEnabled.value)
        }

        binding.btnLoadMoreSongs.setOnClickListener {
            loadMoreSongs()
        }
    }

    private fun observePlaybackControls() {
        val playback = SpotifyApplication.instance.playbackManager
        viewLifecycleOwner.lifecycleScope.launch {
            combine(playback.currentTrack, playback.isPlaying) { track, playing -> track to playing }
                .collectLatest { (track, playing) ->
                    val trackId = track?.youtubeId.orEmpty()
                    val isArtistTrack = trackId.isNotBlank() &&
                        (trackId in activeArtistQueueIds || allArtistTracks.any { it.youtubeId == trackId })
                    binding.btnArtistPlayFab.setImageResource(
                        if (isArtistTrack && playing) R.drawable.ic_pause else R.drawable.ic_play
                    )
                    binding.btnArtistPlayFab.contentDescription =
                        if (isArtistTrack && playing) "Pause artist" else "Play artist"
                }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            playback.isShuffleEnabled.collectLatest { enabled ->
                binding.btnArtistShuffle.backgroundTintList = android.content.res.ColorStateList.valueOf(
                    requireContext().getColor(
                        if (enabled) R.color.spotify_green else R.color.spotify_card_grey
                    )
                )
                binding.btnArtistShuffle.imageTintList = android.content.res.ColorStateList.valueOf(
                    requireContext().getColor(
                        if (enabled) R.color.spotify_black else R.color.spotify_white
                    )
                )
            }
        }
    }

    private fun playArtistDiscography() {
        if (isPreparingArtistQueue) return
        isPreparingArtistQueue = true
        Toast.makeText(context, "Preparing ${artistName}'s albums…", Toast.LENGTH_SHORT).show()

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val releases = (artistAlbums + artistSingles).distinctBy {
                    it.id ?: "${it.name.lowercase()}-${it.year.orEmpty()}"
                }.ifEmpty {
                    withContext(Dispatchers.IO) {
                        NewPipeExtractorHelper.fetchArtistDiscography(artistName, artistChannelUrl)
                            .getOrDefault(emptyList<AlbumDto>() to emptyList())
                            .let { it.first + it.second }
                    }
                }

                val queue = withContext(Dispatchers.IO) {
                    val semaphore = Semaphore(3)
                    releases.map { album ->
                        async {
                            semaphore.withPermit {
                                NewPipeExtractorHelper.fetchAlbumTracks(
                                    album.id,
                                    album.name,
                                    artistName,
                                    album.trackCount
                                ).getOrDefault(emptyList())
                            }
                        }
                    }.awaitAll().flatten().distinctBy { it.youtubeId }
                }.ifEmpty { allArtistTracks.toList() }

                if (queue.isEmpty()) {
                    Toast.makeText(context, "No artist tracks found", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                activeArtistQueueIds = queue.mapTo(mutableSetOf()) { it.youtubeId }
                val playback = SpotifyApplication.instance.playbackManager
                val startIndex = if (playback.isShuffleEnabled.value) queue.indices.random() else 0
                playback.playPlaylistQueue(queue, startIndex)
            } catch (error: Exception) {
                Toast.makeText(
                    context,
                    "Couldn't load artist albums: ${error.localizedMessage ?: "Please try again"}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                isPreparingArtistQueue = false
            }
        }
    }

    private fun loadArtistChannel() {
        binding.progressArtistDetail.visibility = View.VISIBLE
        binding.layoutArtistEmpty.visibility = View.GONE
        binding.recyclerArtistTracks.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            // Start every profile section immediately; none waits for Top Songs or Spotify metadata.
            loadDiscography()
            loadRelatedArtists()

            val avatarUrl = artistAvatarUrl
            // Load the artist's YouTube Music songs immediately; the Spotify
            // embed lookup is not needed to identify or play these tracks.
            val songsResult = withContext(Dispatchers.IO) {
                NewPipeExtractorHelper.fetchArtistSongs(artistName, artistChannelUrl)
            }

            val b = _binding
            if (b != null) {
                b.progressArtistDetail.visibility = View.GONE

                if (songsResult.isSuccess) {
                    val channel = songsResult.getOrNull()
                    if (channel != null) {
                        artistChannelUrl = artistChannelUrl ?: channel.artist.channelUrl
                        val artist = channel.artist.copy(
                            name = artistName,
                            avatarUrl = avatarUrl ?: channel.artist.avatarUrl,
                            channelUrl = artistChannelUrl ?: channel.artist.channelUrl
                        )
                        renderArtist(ArtistChannel(artist, channel.tracks))

                        return@launch
                    }
                }

                val error = songsResult.exceptionOrNull()
                if (error is SourceBrokenException) {
                    Toast.makeText(context, "YouTube Music extractor needs update", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(context, "Failed to load artist", Toast.LENGTH_SHORT).show()
                }
                b.layoutArtistEmpty.visibility = View.VISIBLE
            }
        }
    }

    private fun loadDiscography() {
        viewLifecycleOwner.lifecycleScope.launch {
            val b = _binding ?: return@launch
            b.layoutAlbumsSection.visibility = View.VISIBLE
            b.layoutSinglesSection.visibility = View.VISIBLE
            b.textAlbumsLoading.visibility = View.VISIBLE
            b.textSinglesLoading.visibility = View.VISIBLE
            b.recyclerAlbums.visibility = View.GONE
            b.recyclerSingles.visibility = View.GONE

            val result = withContext(Dispatchers.IO) {
                NewPipeExtractorHelper.fetchArtistDiscography(artistName, artistChannelUrl)
            }

            val currentBinding = _binding ?: return@launch
            val discography = result.getOrDefault(
                emptyList<AlbumDto>() to emptyList<AlbumDto>()
            )
            artistAlbums.clear()
            artistAlbums.addAll(discography.first)
            artistSingles.clear()
            artistSingles.addAll(discography.second)
            albumAdapter.notifyDataSetChanged()
            singlesAdapter.notifyDataSetChanged()

            currentBinding.textAlbumsLoading.visibility = View.GONE
            currentBinding.textSinglesLoading.visibility = View.GONE
            currentBinding.recyclerAlbums.visibility = if (artistAlbums.isNotEmpty()) View.VISIBLE else View.GONE
            currentBinding.recyclerSingles.visibility = if (artistSingles.isNotEmpty()) View.VISIBLE else View.GONE
            currentBinding.layoutAlbumsSection.visibility = if (artistAlbums.isNotEmpty()) View.VISIBLE else View.GONE
            currentBinding.layoutSinglesSection.visibility = if (artistSingles.isNotEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun loadRelatedArtists() {
        viewLifecycleOwner.lifecycleScope.launch {
            val relatedResult = withContext(Dispatchers.IO) {
                NewPipeExtractorHelper.fetchRelatedArtists(artistName)
            }

            val b = _binding
            if (b != null && relatedResult.isSuccess) {
                val list = relatedResult.getOrDefault(emptyList())
                if (list.isNotEmpty()) {
                    relatedArtists.clear()
                    relatedArtists.addAll(list)
                    fansAlsoLikeAdapter.notifyDataSetChanged()
                    b.layoutFansAlsoLikeSection.visibility = View.VISIBLE
                } else {
                    b.layoutFansAlsoLikeSection.visibility = View.GONE
                }
            }
        }
    }

    private fun renderArtist(channel: ArtistChannel) {
        val b = _binding ?: return
        val artist = channel.artist
        b.textTopBarTitle.text = artist.name
        b.textTopBarTitle.visibility = View.VISIBLE

        val avatarUrl = artistAvatarUrl ?: artist.avatarUrl
        if (!avatarUrl.isNullOrEmpty()) {
            Glide.with(this)
                .load(avatarUrl)
                .placeholder(R.drawable.ic_person)
                .error(R.drawable.ic_person)
                .centerCrop()
                .into(b.imgArtistAvatar)
        } else {
            b.imgArtistAvatar.setImageResource(R.drawable.ic_person)
        }

        allArtistTracks.clear()
        allArtistTracks.addAll(channel.tracks)

        artistTracks.clear()
        artistTracks.addAll(allArtistTracks.take(5))
        trackAdapter.updateTracks(artistTracks)
        updateArtistPlayButton()

        isExpanded = false
        b.btnLoadMoreSongs.visibility = if (allArtistTracks.size > 5) View.VISIBLE else View.GONE
        b.btnLoadMoreSongs.text = "Show all"
        b.btnLoadMoreSongs.isEnabled = true

        if (artistTracks.isEmpty()) {
            b.layoutArtistEmpty.visibility = View.VISIBLE
            b.recyclerArtistTracks.visibility = View.GONE
            b.btnLoadMoreSongs.visibility = View.GONE
        } else {
            b.layoutArtistEmpty.visibility = View.GONE
            b.recyclerArtistTracks.visibility = View.VISIBLE
        }
    }

    private fun updateArtistPlayButton() {
        val playback = SpotifyApplication.instance.playbackManager
        val currentId = playback.currentTrack.value?.youtubeId.orEmpty()
        val isArtistTrack = currentId.isNotBlank() &&
            (currentId in activeArtistQueueIds || allArtistTracks.any { it.youtubeId == currentId })
        binding.btnArtistPlayFab.setImageResource(
            if (isArtistTrack && playback.isPlaying.value) R.drawable.ic_pause else R.drawable.ic_play
        )
    }

    private fun loadMoreSongs() {
        val b = _binding ?: return

        if (isExpanded) {
            // Collapse to show only first 5
            artistTracks.clear()
            artistTracks.addAll(allArtistTracks.take(5))
            trackAdapter.updateTracks(artistTracks)
            b.btnLoadMoreSongs.text = "Show all"
            isExpanded = false
        } else {
            // Expand to show all fetched songs (30–50+)
            artistTracks.clear()
            artistTracks.addAll(allArtistTracks)
            trackAdapter.updateTracks(artistTracks)
            b.btnLoadMoreSongs.text = "Show less"
            isExpanded = true
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
