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
import com.spotifyclone.app.SpotifyApplication
import com.spotifyclone.app.data.local.entities.RecentlyPlayed
import com.spotifyclone.app.data.local.entities.RecentSearch
import com.spotifyclone.app.databinding.FragmentSearchBinding
import com.spotifyclone.app.extractor.NewPipeExtractorHelper
import com.spotifyclone.app.extractor.SourceBrokenException
import com.spotifyclone.app.model.TrackDto
import com.spotifyclone.app.ui.adapter.RecentItemDisplay
import com.spotifyclone.app.ui.adapter.RecentSearchAdapter
import com.spotifyclone.app.ui.adapter.SearchSuggestionAdapter
import com.spotifyclone.app.ui.adapter.TrackAdapter
import com.spotifyclone.app.ui.dialog.TrackOptionsHelper
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
    private val suggestions = mutableListOf<String>()
    private val recentItems = mutableListOf<RecentItemDisplay>()

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
                lifecycleScope.launch(Dispatchers.IO) {
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
                    lifecycleScope.launch(Dispatchers.IO) {
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
                lifecycleScope.launch(Dispatchers.IO) {
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
            lifecycleScope.launch(Dispatchers.IO) {
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
        suggestionJob = lifecycleScope.launch {
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
        binding.recyclerSearchResults.visibility = View.GONE
        binding.searchProgress.visibility = View.GONE

        if (recentItems.isNotEmpty()) {
            binding.layoutRecentSearchesContainer.visibility = View.VISIBLE
            binding.textSearchEmpty.visibility = View.GONE
        } else {
            binding.layoutRecentSearchesContainer.visibility = View.GONE
            binding.textSearchEmpty.visibility = View.VISIBLE
            binding.textSearchEmpty.text = "Play what you love • Search for artists, songs, or podcasts"
        }
    }

    private fun observeRecentSearches() {
        lifecycleScope.launch {
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
        isSearching = true
        suggestionJob?.cancel()
        suggestions.clear()
        suggestionAdapter.updateSuggestions(emptyList())

        binding.searchProgress.visibility = View.VISIBLE
        binding.textSearchEmpty.visibility = View.GONE
        binding.layoutRecentSearchesContainer.visibility = View.GONE
        binding.cardSearchSuggestions.visibility = View.GONE
        binding.recyclerSearchResults.visibility = View.VISIBLE

        // Record query in recent searches
        lifecycleScope.launch(Dispatchers.IO) {
            SpotifyApplication.instance.database.recentSearchDao().insertSearch(
                RecentSearch(query = query, timestamp = System.currentTimeMillis())
            )
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val result = NewPipeExtractorHelper.searchYouTubeMusic(query)

            withContext(Dispatchers.Main) {
                isSearching = false
                binding.searchProgress.visibility = View.GONE

                if (result.isSuccess) {
                    val tracks = result.getOrDefault(emptyList())
                    searchResults.clear()
                    searchResults.addAll(tracks)
                    trackAdapter.updateTracks(searchResults)

                    if (tracks.isEmpty()) {
                        binding.textSearchEmpty.visibility = View.VISIBLE
                        binding.textSearchEmpty.text = "No results found for \"$query\""
                    }
                } else {
                    val error = result.exceptionOrNull()
                    if (error is SourceBrokenException) {
                        binding.textSearchEmpty.visibility = View.VISIBLE
                        binding.textSearchEmpty.text = "Source broken! Please update the app via GitHub Releases."
                    } else {
                        Toast.makeText(context, "Search error: ${error?.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
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
