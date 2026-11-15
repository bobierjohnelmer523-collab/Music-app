package com.spotifyclone.app.ui.search

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.spotifyclone.app.SpotifyApplication
import com.spotifyclone.app.databinding.FragmentSearchBinding
import com.spotifyclone.app.extractor.NewPipeExtractorHelper
import com.spotifyclone.app.extractor.SourceBrokenException
import com.spotifyclone.app.model.TrackDto
import com.spotifyclone.app.ui.adapter.TrackAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SearchFragment : Fragment() {

    private var _binding: FragmentSearchBinding? = null
    private val binding get() = _binding!!

    private lateinit var trackAdapter: TrackAdapter
    private val searchResults = mutableListOf<TrackDto>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupSearchInput()
    }

    private fun setupRecyclerView() {
        trackAdapter = TrackAdapter(
            tracks = searchResults,
            onTrackClick = { track, position ->
                SpotifyApplication.instance.playbackManager.playQueue(searchResults, position)
            },
            onMenuClick = { track ->
                SpotifyApplication.instance.downloadHelper.downloadTrack(track) { success ->
                    activity?.runOnUiThread {
                        val msg = if (success) "Track downloaded for offline playback" else "Download failed"
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )

        binding.recyclerSearchResults.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = trackAdapter
        }
    }

    private fun setupSearchInput() {
        binding.editSearchQuery.setOnEditorActionListener { textView, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val query = textView.text.toString().trim()
                if (query.isNotEmpty()) {
                    performSearch(query)
                }
                true
            } else {
                false
            }
        }
    }

    private fun performSearch(query: String) {
        binding.searchProgress.visibility = View.VISIBLE
        binding.textSearchEmpty.visibility = View.GONE

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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
