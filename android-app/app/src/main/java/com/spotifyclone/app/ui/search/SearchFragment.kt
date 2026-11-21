package com.spotifyclone.app.ui.search

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.spotifyclone.app.R
import com.spotifyclone.app.SpotifyApplication
import com.spotifyclone.app.data.local.entities.RecentlyPlayed
import com.spotifyclone.app.data.local.entities.RecentSearch
import com.spotifyclone.app.databinding.FragmentSearchBinding
import com.spotifyclone.app.extractor.NewPipeExtractorHelper
import com.spotifyclone.app.extractor.SourceBrokenException
import com.spotifyclone.app.model.ArtistDto
import com.spotifyclone.app.model.SpotifyPlaylistSummary
import com.spotifyclone.app.model.TrackDto
import com.spotifyclone.app.spotify.SpotifyAuthManager
import com.spotifyclone.app.spotify.SpotifyImportStore
import com.spotifyclone.app.spotify.SpotifyRepository
import com.spotifyclone.app.ui.adapter.ArtistAdapter
import com.spotifyclone.app.ui.adapter.RecentItemDisplay
import com.spotifyclone.app.ui.adapter.RecentSearchAdapter
import com.spotifyclone.app.ui.adapter.SearchSuggestionAdapter
import com.spotifyclone.app.ui.adapter.SpotifyPlaylistRowAdapter
import com.spotifyclone.app.ui.adapter.TrackAdapter
import com.spotifyclone.app.ui.dialog.TrackOptionsHelper
import com.spotifyclone.app.ui.artist.ArtistDetailFragment
import com.spotifyclone.app.ui.playlist.SpotifyPlaylistDetailFragment
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine

class SearchFragment : Fragment() {

    private var _binding: FragmentSearchBinding? = null
    private val binding get() = _binding!!

    private lateinit var trackAdapter: TrackAdapter
    private lateinit var suggestionAdapter: SearchSuggestionAdapter
    private lateinit var recentSearchAdapter: RecentSearchAdapter

    private val searchResults = mutableListOf<TrackDto>()
    private val spotifyPlaylistResults = mutableListOf<SpotifyPlaylistSummary>()
    private val artistResults = mutableListOf<ArtistDto>()
    private val suggestions = mutableListOf<String>()
    private val recentItems = mutableListOf<RecentItemDisplay>()

    private lateinit var spotifyPlaylistAdapter: SpotifyPlaylistRowAdapter
    private lateinit var artistAdapter: ArtistAdapter

    private var suggestionJob: Job? = null
    private var isProgrammaticTextChange = false
    private var isSearching = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerViews()
        setupSearchInput()
        observeRecentSearches()
    }

    private fun setupRecyclerViews() {
        // 1. Search Results List
        trackAdapter = TrackAdapter(
            tracks = searchResults,
            onTrackClick = { _, position ->
                val track = searchResults[position]
                SpotifyApplication.instance.playbackManager.playQueue(searchResults, position)
                // Record in recently played so it appears in recent searches with cover art
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    SpotifyApplication.instance.database.recentlyPlayedDao().recordRecentlyPlayed(
                        RecentlyPlayed(
                            title = track.title,
                            subtitle = track.artist ?: "Unknown Artist",
                            thumbnailUrl = track.thumbnailUrl,
                            type = "track",
                            dataId = track.youtubeId
                        )
                    )
                }
            },
            onMenuClick = { track ->
                TrackOptionsHelper.showTrackOptions(requireContext(), track)
            }
        )
        binding.recyclerSearchResults.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = trackAdapter
        }

        // Spotify playlist matches row (shown above track results when logged in)
        spotifyPlaylistAdapter = SpotifyPlaylistRowAdapter(
            playlists = spotifyPlaylistResults,
            onPlaylistClick = { playlist ->
                val fragment = SpotifyPlaylistDetailFragment.forPlaylist(
                    playlistId = playlist.id,
                    title = playlist.name,
                    description = playlist.description,
                    coverUrl = playlist.images?.maxByOrNull { it.width ?: 0 }?.url
                        ?: playlist.images?.lastOrNull()?.url
                )
                parentFragmentManager.beginTransaction()
                    .replace(R.id.fragment_container, fragment)
                    .addToBackStack(null)
                    .commit()
            }
        )
        binding.recyclerSpotifyPlaylists.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = spotifyPlaylistAdapter
        }

        // Artist matches row (horizontal cards)
        artistAdapter = ArtistAdapter(
            artists = artistResults,
            onArtistClick = { artist ->
                val fragment = ArtistDetailFragment.forArtist(artist.name, artist.avatarUrl, null, artist.channelUrl)
                parentFragmentManager.beginTransaction()
                    .replace(R.id.fragment_container, fragment)
                    .addToBackStack(null)
                    .commit()
            }
        )
        binding.recyclerArtists.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = artistAdapter
        }

        // 2. Search Autocomplete Suggestions Dropdown
        suggestionAdapter = SearchSuggestionAdapter(
            suggestions = suggestions,
            onSuggestionClick = { selectedSuggestion ->
                suggestionJob?.cancel()
                isProgrammaticTextChange = true
                binding.cardSearchSuggestions.visibility = View.GONE
                suggestions.clear()
                suggestionAdapter.updateSuggestions(emptyList())

                binding.editSearchQuery.setText(selectedSuggestion)
                binding.editSearchQuery.setSelection(selectedSuggestion.length)
                binding.editSearchQuery.clearFocus()
                hideKeyboard()
                performSearch(selectedSuggestion)
            }
        )
        binding.recyclerSuggestions.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = suggestionAdapter
        }

        // 3. Unified Recent Searches (Queries & Played Songs)
        recentSearchAdapter = RecentSearchAdapter(
            items = recentItems,
            onItemClick = { item ->
                if (item.isQuery) {
                    val query = item.queryText ?: item.title
                    suggestionJob?.cancel()
                    isProgrammaticTextChange = true
                    binding.cardSearchSuggestions.visibility = View.GONE
                    binding.editSearchQuery.setText(query)
                    binding.editSearchQuery.setSelection(query.length)
                    binding.editSearchQuery.clearFocus()
                    hideKeyboard()
                    performSearch(query)
                } else if (item.trackDto != null) {
                    // Tap on recently played song -> play it immediately
                    SpotifyApplication.instance.playbackManager.playSingle(item.trackDto)
                    // Refresh its timestamp in recently played
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        SpotifyApplication.instance.database.recentlyPlayedDao().recordRecentlyPlayed(
                            RecentlyPlayed(
                                title = item.trackDto.title,
                                subtitle = item.trackDto.artist ?: "Unknown Artist",
                                thumbnailUrl = item.trackDto.thumbnailUrl,
                                type = "track",
                                dataId = item.trackDto.youtubeId
                            )
                        )
                    }
                }
            },
            onAddToPlaylistClick = { item ->
                if (item.trackDto != null) {
                    TrackOptionsHelper.showAddToPlaylistDialog(requireContext(), item.trackDto)
                }
            },
            onDeleteClick = { item ->
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    if (item.isQuery && item.queryText != null) {
                        SpotifyApplication.instance.database.recentSearchDao().deleteSearch(item.queryText)
                    } else if (item.playedId != null) {
                        SpotifyApplication.instance.database.recentlyPlayedDao().deleteById(item.playedId)
                    } else if (item.trackDto != null) {
                        SpotifyApplication.instance.database.recentlyPlayedDao().deleteByDataId(item.trackDto.youtubeId)
                    }
                }
            }
        )
        binding.recyclerRecentSearches.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = recentSearchAdapter
        }

        // Clear All Recent Searches and Played
        binding.btnClearAllRecent.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                SpotifyApplication.instance.database.recentSearchDao().clearAll()
                SpotifyApplication.instance.database.recentlyPlayedDao().clearAll()
            }
        }
    }

    private fun setupSearchInput() {
        binding.btnClearSearchText.setOnClickListener {
            suggestionJob?.cancel()
            isProgrammaticTextChange = true
            binding.editSearchQuery.setText("")
            binding.cardSearchSuggestions.visibility = View.GONE
            suggestions.clear()
            suggestionAdapter.updateSuggestions(emptyList())
            switchToRecentSearchesMode()
        }

        binding.editSearchQuery.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                binding.cardSearchSuggestions.visibility = View.GONE
            }
        }

        binding.editSearchQuery.setOnEditorActionListener { textView, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val query = textView.text.toString().trim()
                if (query.isNotEmpty()) {
                    suggestionJob?.cancel()
                    isProgrammaticTextChange = true
                    binding.cardSearchSuggestions.visibility = View.GONE
                    binding.editSearchQuery.clearFocus()
                    hideKeyboard()
                    performSearch(query)
                }
                true
            } else {
                false
            }
        }

        binding.editSearchQuery.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (isProgrammaticTextChange) {
                    isProgrammaticTextChange = false
                    return
                }

                val query = s?.toString()?.trim() ?: ""

                if (query.isEmpty()) {
                    binding.btnClearSearchText.visibility = View.GONE
                    binding.cardSearchSuggestions.visibility = View.GONE
                    suggestionJob?.cancel()
                    suggestions.clear()
                    suggestionAdapter.updateSuggestions(emptyList())
                    switchToRecentSearchesMode()
                } else {
                    binding.btnClearSearchText.visibility = View.VISIBLE
                    binding.layoutRecentSearchesContainer.visibility = View.GONE
                    binding.textSearchEmpty.visibility = View.GONE
                    if (binding.editSearchQuery.hasFocus()) {
                        fetchSuggestionsDebounced(query)
                    }
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun fetchSuggestionsDebounced(query: String) {
        suggestionJob?.cancel()
        suggestionJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(180) // 180ms debounce
            val results = NewPipeExtractorHelper.fetchSearchSuggestions(query)
            if (isActive && results.isNotEmpty() &&
                binding.editSearchQuery.hasFocus() &&
                !isSearching &&
                binding.editSearchQuery.text.toString().trim() == query) {
                suggestions.clear()
                suggestions.addAll(results)
                suggestionAdapter.updateSuggestions(suggestions)
                binding.cardSearchSuggestions.visibility = View.VISIBLE
            } else {
                binding.cardSearchSuggestions.visibility = View.GONE
            }
        }
    }

    private fun switchToRecentSearchesMode() {
        binding.cardSearchSuggestions.visibility = View.GONE
        binding.layoutSearchResultsArea.visibility = View.GONE
        binding.searchProgress.visibility = View.GONE

        if (recentItems.isNotEmpty()) {
            binding.layoutRecentSearchesContainer.visibility = View.VISIBLE
            binding.textSearchEmpty.visibility = View.GONE
        } else {
            binding.layoutRecentSearchesContainer.visibility = View.GONE
            binding.textSearchEmpty.visibility = View.GONE
        }
    }

    private fun observeRecentSearches() {
        viewLifecycleOwner.lifecycleScope.launch {
            combine(
                SpotifyApplication.instance.database.recentSearchDao().getAllRecentSearches(),
                SpotifyApplication.instance.database.recentlyPlayedDao().getAllRecentPlayed()
            ) { queries, playedList ->
                val combined = mutableListOf<RecentItemDisplay>()
                val songTitlesLower = playedList.map { it.title.lowercase().trim() }.toSet()

                // 1. Search text queries (skip if query matches a played song title to avoid duplicate)
                queries.forEach { s ->
                    if (!songTitlesLower.contains(s.query.lowercase().trim())) {
                        combined.add(
                            RecentItemDisplay(
                                id = "query_${s.query}",
                                title = s.query,
                                subtitle = "Search query",
                                isQuery = true,
                                queryText = s.query,
                                timestamp = s.timestamp
                            )
                        )
                    }
                }

                // 2. Recently played tracks & playlists (with cover art, + button, and x button)
                playedList.forEach { p ->
                    val isPlaylist = p.type.equals("playlist", ignoreCase = true)
                    val sub = if (isPlaylist) "Playlist" else "Song • ${p.subtitle ?: "Artist"}"
                    val trackDto = if (!isPlaylist) {
                        TrackDto(
                            youtubeId = p.dataId,
                            title = p.title,
                            artist = p.subtitle,
                            durationSeconds = 0L,
                            thumbnailUrl = p.thumbnailUrl
                        )
                    } else null

                    combined.add(
                        RecentItemDisplay(
                            id = "played_${p.id}_${p.dataId}",
                            title = p.title,
                            subtitle = sub,
                            thumbnailUrl = p.thumbnailUrl,
                            isQuery = false,
                            trackDto = trackDto,
                            playlistId = if (isPlaylist) p.dataId else null,
                            playedId = p.id,
                            timestamp = p.timestamp
                        )
                    )
                }

                combined.sortByDescending { it.timestamp }
                combined.take(35)
            }.collectLatest { list ->
                if (_binding == null) return@collectLatest
                recentItems.clear()
                recentItems.addAll(list)
                recentSearchAdapter.updateItems(recentItems)

                if (binding.editSearchQuery.text.isNullOrEmpty()) {
                    switchToRecentSearchesMode()
                }
            }
        }
    }

    private fun performSearch(query: String) {
        // Spotify link/URI pasted -> import the playlist directly
        if (SpotifyRepository.isSpotifyLink(query)) {
            handleSpotifyLink(query)
            return
        }

        isSearching = true
        suggestionJob?.cancel()
        suggestions.clear()
        suggestionAdapter.updateSuggestions(emptyList())

        binding.searchProgress.visibility = View.VISIBLE
        binding.textSearchEmpty.visibility = View.GONE
        binding.layoutRecentSearchesContainer.visibility = View.GONE
        binding.cardSearchSuggestions.visibility = View.GONE
        binding.layoutSearchResultsArea.visibility = View.VISIBLE
        binding.layoutArtistsRow.visibility = View.GONE
        binding.layoutSpotifyPlaylistsRow.visibility = View.GONE
        artistResults.clear()
        artistAdapter.notifyDataSetChanged()
        spotifyPlaylistResults.clear()
        spotifyPlaylistAdapter.setItems(emptyList())

        // Record query in recent searches
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            SpotifyApplication.instance.database.recentSearchDao().insertSearch(
                RecentSearch(query = query, timestamp = System.currentTimeMillis())
            )
        }

        // Also look up matching Spotify playlists when connected
        if (SpotifyAuthManager.isLoggedIn()) {
            viewLifecycleOwner.lifecycleScope.launch {
                val playlists = SpotifyRepository.searchPlaylists(query, limit = 10)
                withContext(Dispatchers.Main) {
                    if (_binding == null) return@withContext
                    spotifyPlaylistResults.clear()
                    spotifyPlaylistResults.addAll(playlists)
                    spotifyPlaylistAdapter.setItems(spotifyPlaylistResults)
                    binding.layoutSpotifyPlaylistsRow.visibility =
                        if (playlists.isNotEmpty()) View.VISIBLE else View.GONE
                }
            }
        }

        // Search YouTube Music for artists
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val artistResult = NewPipeExtractorHelper.searchYouTubeMusicArtists(query)
            withContext(Dispatchers.Main) {
                if (_binding == null) return@withContext
                if (artistResult.isSuccess) {
                    val artists = artistResult.getOrDefault(emptyList())
                    artistResults.clear()
                    artistResults.addAll(artists)
                    artistAdapter.notifyDataSetChanged()
                    binding.layoutArtistsRow.visibility =
                        if (artists.isNotEmpty()) View.VISIBLE else View.GONE
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val result = NewPipeExtractorHelper.searchYouTubeMusic(query)

            withContext(Dispatchers.Main) {
                val b = _binding ?: return@withContext
                isSearching = false
                b.searchProgress.visibility = View.GONE

                if (result.isSuccess) {
                    val tracks = result.getOrDefault(emptyList())
                    searchResults.clear()
                    searchResults.addAll(tracks)
                    trackAdapter.updateTracks(searchResults)

                    if (tracks.isEmpty()) {
                        b.textSearchEmpty.visibility = View.VISIBLE
                        b.textSearchEmpty.text = "No results found for \"$query\""
                    }
                } else {
                    val error = result.exceptionOrNull()
                    if (error is SourceBrokenException) {
                        b.textSearchEmpty.visibility = View.VISIBLE
                        b.textSearchEmpty.text = "Source broken! Please update the app via GitHub Releases."
                    } else {
                        Toast.makeText(context, "Search error: ${error?.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    /** Handle a pasted Spotify link: open playlist import or explain unsupported types. */
    private fun handleSpotifyLink(query: String) {
        suggestionJob?.cancel()
        isProgrammaticTextChange = true
        binding.cardSearchSuggestions.visibility = View.GONE
        binding.editSearchQuery.clearFocus()
        hideKeyboard()

        viewLifecycleOwner.lifecycleScope.launch {
            val playlistId = SpotifyRepository.parsePlaylistIdAsync(query)
            if (playlistId == null) {
                Toast.makeText(
                    context,
                    "Only Spotify playlist links are supported",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            // Fetch metadata (works for public playlists without login) and
            // save the playlist to Your Library so it persists.
            binding.searchProgress.visibility = View.VISIBLE
            val meta = withContext(Dispatchers.IO) {
                SpotifyRepository.getPlaylistMeta(playlistId)
            }
            binding.searchProgress.visibility = View.GONE

            if (meta == null) {
                Toast.makeText(
                    context,
                    "Couldn't load that playlist — it may be private",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            SpotifyImportStore.add(
                requireContext(),
                SpotifyImportStore.ImportedPlaylist(
                    id = playlistId,
                    title = meta.title,
                    coverUrl = meta.coverUrl,
                    addedAt = System.currentTimeMillis()
                )
            )

            val fragment = SpotifyPlaylistDetailFragment.forPlaylist(
                playlistId = playlistId,
                title = meta.title,
                description = meta.description,
                coverUrl = meta.coverUrl
            )
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .addToBackStack(null)
                .commit()
        }
    }

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.editSearchQuery.windowToken, 0)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        suggestionJob?.cancel()
        _binding = null
    }
}
