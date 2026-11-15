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
import com.spotifyclone.app.data.local.entities.RecentSearch
import com.spotifyclone.app.databinding.FragmentSearchBinding
import com.spotifyclone.app.extractor.NewPipeExtractorHelper
import com.spotifyclone.app.extractor.SourceBrokenException
import com.spotifyclone.app.model.TrackDto
import com.spotifyclone.app.ui.adapter.RecentSearchAdapter
import com.spotifyclone.app.ui.adapter.SearchSuggestionAdapter
import com.spotifyclone.app.ui.adapter.TrackAdapter
import com.spotifyclone.app.ui.dialog.TrackOptionsHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class SearchFragment : Fragment() {

    private var _binding: FragmentSearchBinding? = null
    private val binding get() = _binding!!

    private lateinit var trackAdapter: TrackAdapter
    private lateinit var suggestionAdapter: SearchSuggestionAdapter
    private lateinit var recentSearchAdapter: RecentSearchAdapter

    private val searchResults = mutableListOf<TrackDto>()
    private val suggestions = mutableListOf<String>()
    private val recentSearches = mutableListOf<RecentSearch>()

    private var suggestionJob: Job? = null

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
        // 1. Search Results
        trackAdapter = TrackAdapter(
            tracks = searchResults,
            onTrackClick = { _, position ->
                SpotifyApplication.instance.playbackManager.playQueue(searchResults, position)
            },
            onMenuClick = { track ->
                TrackOptionsHelper.showTrackOptions(requireContext(), track)
            }
        )
        binding.recyclerSearchResults.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = trackAdapter
        }

        // 2. Search Autocomplete Suggestions
        suggestionAdapter = SearchSuggestionAdapter(
            suggestions = suggestions,
            onSuggestionClick = { selectedSuggestion ->
                binding.editSearchQuery.setText(selectedSuggestion)
                binding.editSearchQuery.setSelection(selectedSuggestion.length)
                binding.cardSearchSuggestions.visibility = View.GONE
                hideKeyboard()
                performSearch(selectedSuggestion)
            }
        )
        binding.recyclerSuggestions.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = suggestionAdapter
        }

        // 3. Recent Searches
        recentSearchAdapter = RecentSearchAdapter(
            searches = recentSearches,
            onSearchClick = { query ->
                binding.editSearchQuery.setText(query)
                binding.editSearchQuery.setSelection(query.length)
                binding.cardSearchSuggestions.visibility = View.GONE
                hideKeyboard()
                performSearch(query)
            },
            onDeleteClick = { query ->
                lifecycleScope.launch(Dispatchers.IO) {
                    SpotifyApplication.instance.database.recentSearchDao().deleteSearch(query)
                }
            }
        )
        binding.recyclerRecentSearches.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = recentSearchAdapter
        }

        binding.btnClearAllRecent.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                SpotifyApplication.instance.database.recentSearchDao().clearAll()
            }
        }
    }

    private fun setupSearchInput() {
        binding.btnClearSearchText.setOnClickListener {
            binding.editSearchQuery.setText("")
            switchToRecentSearchesMode()
        }

        binding.editSearchQuery.setOnEditorActionListener { textView, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val query = textView.text.toString().trim()
                if (query.isNotEmpty()) {
                    hideKeyboard()
                    binding.cardSearchSuggestions.visibility = View.GONE
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
                val query = s?.toString()?.trim() ?: ""

                if (query.isEmpty()) {
                    binding.btnClearSearchText.visibility = View.GONE
                    binding.cardSearchSuggestions.visibility = View.GONE
                    switchToRecentSearchesMode()
                    suggestionJob?.cancel()
                } else {
                    binding.btnClearSearchText.visibility = View.VISIBLE
                    binding.layoutRecentSearchesContainer.visibility = View.GONE
                    binding.textSearchEmpty.visibility = View.GONE
                    fetchSuggestionsDebounced(query)
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
            if (isActive && results.isNotEmpty() && binding.editSearchQuery.text.toString().trim() == query) {
                suggestions.clear()
                suggestions.addAll(results)
                suggestionAdapter.updateSuggestions(suggestions)
                binding.cardSearchSuggestions.visibility = View.VISIBLE
            } else if (results.isEmpty()) {
                binding.cardSearchSuggestions.visibility = View.GONE
            }
        }
    }

    private fun switchToRecentSearchesMode() {
        binding.cardSearchSuggestions.visibility = View.GONE
        binding.recyclerSearchResults.visibility = View.GONE
        binding.searchProgress.visibility = View.GONE

        if (recentSearches.isNotEmpty()) {
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
            SpotifyApplication.instance.database.recentSearchDao()
                .getAllRecentSearches()
                .collectLatest { list ->
                    recentSearches.clear()
                    recentSearches.addAll(list)
                    recentSearchAdapter.updateSearches(recentSearches)

                    // If search query is currently empty, show recent searches
                    if (binding.editSearchQuery.text.isNullOrEmpty()) {
                        switchToRecentSearchesMode()
                    }
                }
        }
    }

    private fun performSearch(query: String) {
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
