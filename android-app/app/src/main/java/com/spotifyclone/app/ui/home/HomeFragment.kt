package com.spotifyclone.app.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.spotifyclone.app.SpotifyApplication
import com.spotifyclone.app.databinding.FragmentHomeBinding
import com.spotifyclone.app.extractor.NewPipeExtractorHelper
import com.spotifyclone.app.extractor.SourceBrokenException
import com.spotifyclone.app.model.TrackDto
import com.spotifyclone.app.ui.adapter.TrackAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private lateinit var trackAdapter: TrackAdapter
    private val quickPicks = mutableListOf<TrackDto>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setGreeting()
        setupRecyclerView()
        loadQuickPicks()
    }

    private fun setGreeting() {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val greeting = when (hour) {
            in 4..11 -> "Good morning"
            in 12..17 -> "Good afternoon"
            else -> "Good evening"
        }
        binding.textGreeting.text = greeting
    }

    private fun setupRecyclerView() {
        trackAdapter = TrackAdapter(
            tracks = quickPicks,
            onTrackClick = { track, position ->
                SpotifyApplication.instance.playbackManager.playQueue(quickPicks, position)
            },
            onMenuClick = { track ->
                // Quick download or add to playlist
                SpotifyApplication.instance.downloadHelper.downloadTrack(track) { success ->
                    activity?.runOnUiThread {
                        val msg = if (success) "Track downloaded for offline playback!" else "Download failed"
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )

        binding.recyclerQuickPicks.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = trackAdapter
        }
    }

    private fun loadQuickPicks() {
        binding.progressBarHome.visibility = View.VISIBLE

        lifecycleScope.launch(Dispatchers.IO) {
            // Search trending music or popular hits on YouTube Music
            val result = NewPipeExtractorHelper.searchYouTubeMusic("Top Hits 2024")
            withContext(Dispatchers.Main) {
                binding.progressBarHome.visibility = View.GONE

                if (result.isSuccess) {
                    quickPicks.clear()
                    quickPicks.addAll(result.getOrDefault(emptyList()))
                    trackAdapter.updateTracks(quickPicks)
                } else {
                    val error = result.exceptionOrNull()
                    if (error is SourceBrokenException) {
                        Toast.makeText(context, "YouTube Music extractor needs update", Toast.LENGTH_LONG).show()
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
