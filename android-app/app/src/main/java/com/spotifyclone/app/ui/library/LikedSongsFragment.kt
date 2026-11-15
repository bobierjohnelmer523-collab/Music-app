package com.spotifyclone.app.ui.library

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.spotifyclone.app.SpotifyApplication
import com.spotifyclone.app.data.local.entities.LikedSong
import com.spotifyclone.app.databinding.FragmentLikedSongsBinding
import com.spotifyclone.app.model.TrackDto
import com.spotifyclone.app.ui.adapter.TrackAdapter
import com.spotifyclone.app.ui.dialog.TrackOptionsHelper
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class LikedSongsFragment : Fragment() {

    private var _binding: FragmentLikedSongsBinding? = null
    private val binding get() = _binding!!

    private lateinit var trackAdapter: TrackAdapter
    private val likedTracks = mutableListOf<TrackDto>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLikedSongsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupListeners()
        observeLikedSongs()
    }

    private fun setupRecyclerView() {
        trackAdapter = TrackAdapter(
            tracks = likedTracks,
            onTrackClick = { _, position ->
                if (likedTracks.isNotEmpty()) {
                    SpotifyApplication.instance.playbackManager.playQueue(likedTracks, position)
                }
            },
            onMenuClick = { track ->
                TrackOptionsHelper.showTrackOptions(requireContext(), track)
            }
        )

        binding.recyclerLikedSongs.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = trackAdapter
        }
    }

    private fun setupListeners() {
        binding.btnBackLiked.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        binding.fabPlayLiked.setOnClickListener {
            if (likedTracks.isNotEmpty()) {
                SpotifyApplication.instance.playbackManager.playQueue(likedTracks, 0)
            } else {
                Toast.makeText(context, "No liked songs to play", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun observeLikedSongs() {
        lifecycleScope.launch {
            SpotifyApplication.instance.database.likedSongDao()
                .getAllLikedSongs()
                .collectLatest { list ->
                    likedTracks.clear()
                    likedTracks.addAll(list.map { song ->
                        TrackDto(
                            youtubeId = song.youtubeId,
                            title = song.title,
                            artist = song.artist,
                            durationSeconds = song.durationSeconds,
                            thumbnailUrl = song.thumbnailUrl
                        )
                    })
                    trackAdapter.updateTracks(likedTracks)

                    val count = list.size
                    binding.textLikedHeaderSubtitle.text = "$count ${if (count == 1) "song" else "songs"}"
                    binding.textEmptyLiked.visibility = if (count == 0) View.VISIBLE else View.GONE
                }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
