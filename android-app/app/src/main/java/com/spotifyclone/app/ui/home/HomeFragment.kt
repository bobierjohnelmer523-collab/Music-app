package com.spotifyclone.app.ui.home

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.spotifyclone.app.R
import com.spotifyclone.app.SpotifyApplication
import com.spotifyclone.app.api.ApiClient
import com.spotifyclone.app.data.local.entities.RecentlyPlayed
import com.spotifyclone.app.databinding.FragmentHomeBinding
import com.spotifyclone.app.extractor.NewPipeExtractorHelper
import com.spotifyclone.app.extractor.SourceBrokenException
import com.spotifyclone.app.model.TrackDto
import com.spotifyclone.app.ui.adapter.RecentGridAdapter
import com.spotifyclone.app.ui.adapter.TrackAdapter
import com.spotifyclone.app.ui.auth.AuthActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private lateinit var trackAdapter: TrackAdapter
    private lateinit var recentGridAdapter: RecentGridAdapter

    private val quickPicks = mutableListOf<TrackDto>()
    private val recentItems = mutableListOf<RecentlyPlayed>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ApiClient.init(requireContext())
        setupProfileAvatar()
        setupRecentGrid()
        setupRecyclerView()
        setupFilterChips()
        observeRecentlyPlayed()
        loadQuickPicks()
    }

    override fun onResume() {
        super.onResume()
        setupProfileAvatar()
    }

    private fun setupProfileAvatar() {
        if (ApiClient.isLoggedIn()) {
            val username = ApiClient.getUsername() ?: "User"
            val initial = username.firstOrNull()?.uppercaseChar()?.toString() ?: "U"
            binding.textAvatarInitial.text = initial

            binding.btnProfileAvatar.setOnClickListener {
                showUserProfileDialog(username)
            }
        } else {
            binding.textAvatarInitial.text = "👤"
            binding.btnProfileAvatar.setOnClickListener {
                startActivity(Intent(requireContext(), AuthActivity::class.java))
            }
        }
    }

    private fun showUserProfileDialog(username: String) {
        AlertDialog.Builder(requireContext())
            .setTitle("Account")
            .setMessage("Logged in as: $username\nCloud Database: Neon PostgreSQL (Render)\nStatus: Connected")
            .setPositiveButton("Log Out") { _, _ ->
                ApiClient.clearAuth()
                setupProfileAvatar()
                Toast.makeText(context, "Logged out successfully", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun setupRecentGrid() {
        recentGridAdapter = RecentGridAdapter(
            items = recentItems,
            onItemClick = { item ->
                // Play track
                val track = TrackDto(
                    youtubeId = item.dataId,
                    title = item.title,
                    artist = item.subtitle,
                    durationSeconds = null,
                    thumbnailUrl = item.thumbnailUrl
                )
                SpotifyApplication.instance.playbackManager.playSingle(track)
            }
        )

        binding.recyclerRecentGrid.apply {
            layoutManager = GridLayoutManager(context, 2)
            adapter = recentGridAdapter
        }
    }

    private fun setupRecyclerView() {
        trackAdapter = TrackAdapter(
            tracks = quickPicks,
            onTrackClick = { _, position ->
                SpotifyApplication.instance.playbackManager.playQueue(quickPicks, position)
            },
            onMenuClick = { track ->
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

    private fun setupFilterChips() {
        val chips = listOf(
            binding.chipAll to "Top Hits 2024",
            binding.chipMusic to "Popular Songs Philippines",
            binding.chipPodcasts to "Popular Podcasts",
            binding.chipAudiobooks to "Audiobooks"
        )

        for ((chip, query) in chips) {
            chip.setOnClickListener {
                // Highlight clicked chip
                for ((c, _) in chips) {
                    if (c == chip) {
                        c.setBackgroundResource(R.drawable.chip_selected_bg)
                        c.setTextColor(resources.getColor(R.color.spotify_black, null))
                    } else {
                        c.setBackgroundResource(R.drawable.chip_unselected_bg)
                        c.setTextColor(resources.getColor(R.color.spotify_white, null))
                    }
                }
                loadCustomQuery(query)
            }
        }
    }

    private fun observeRecentlyPlayed() {
        lifecycleScope.launch {
            SpotifyApplication.instance.database.recentlyPlayedDao()
                .getRecentItems()
                .collectLatest { items ->
                    if (items.isNotEmpty()) {
                        recentItems.clear()
                        recentItems.addAll(items)
                        recentGridAdapter.updateItems(recentItems)
                        binding.recyclerRecentGrid.visibility = View.VISIBLE
                    } else {
                        // Seed default popular cards if user hasn't played anything yet
                        seedDefaultRecentItems()
                    }
                }
        }
    }

    private fun seedDefaultRecentItems() {
        lifecycleScope.launch(Dispatchers.IO) {
            val defaults = listOf(
                RecentlyPlayed(title = "Top Songs - Philippines", subtitle = "Playlist", thumbnailUrl = null, type = "playlist", dataId = "PL4fGSIFgk5kWV6Vp_s99pA4"),
                RecentlyPlayed(title = "Justin Bieber - All Songs", subtitle = "Artist", thumbnailUrl = null, type = "playlist", dataId = "PL8c4G7N5D-3Yj4w"),
                RecentlyPlayed(title = "Christmas Kisses", subtitle = "Ariana Grande", thumbnailUrl = null, type = "playlist", dataId = "PLi7M3wD_9vP4"),
                RecentlyPlayed(title = "Top Hits 2024", subtitle = "Playlist", thumbnailUrl = null, type = "playlist", dataId = "PLxA687tYuMWgQ"),
                RecentlyPlayed(title = "OPM Classics", subtitle = "Various Artists", thumbnailUrl = null, type = "playlist", dataId = "PLOPM123"),
                RecentlyPlayed(title = "Coffeehouse Acoustic", subtitle = "Chill", thumbnailUrl = null, type = "playlist", dataId = "PLCHILL456")
            )
            for (item in defaults) {
                SpotifyApplication.instance.database.recentlyPlayedDao().insert(item)
            }
        }
    }

    private fun loadQuickPicks() {
        loadCustomQuery("Top Hits 2024")
    }

    private fun loadCustomQuery(query: String) {
        binding.progressBarHome.visibility = View.VISIBLE

        lifecycleScope.launch(Dispatchers.IO) {
            val result = NewPipeExtractorHelper.searchYouTubeMusic(query)
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
