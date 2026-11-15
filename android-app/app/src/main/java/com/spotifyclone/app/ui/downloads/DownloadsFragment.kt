package com.spotifyclone.app.ui.downloads

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.spotifyclone.app.SpotifyApplication
import com.spotifyclone.app.data.local.entities.DownloadedTrack
import com.spotifyclone.app.data.local.entities.LocalPlaylist
import com.spotifyclone.app.databinding.FragmentDownloadsBinding
import com.spotifyclone.app.model.TrackDto
import com.spotifyclone.app.ui.adapter.DownloadedTrackAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class DownloadsFragment : Fragment() {

    private var _binding: FragmentDownloadsBinding? = null
    private val binding get() = _binding!!

    private lateinit var downloadAdapter: DownloadedTrackAdapter
    private val downloadedTracks = mutableListOf<DownloadedTrack>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDownloadsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupListeners()
        observeDownloadedTracks()
    }

    private fun setupRecyclerView() {
        downloadAdapter = DownloadedTrackAdapter(
            tracks = downloadedTracks,
            onTrackClick = { downloadedTrack, position ->
                val trackDtos = downloadedTracks.map { t ->
                    TrackDto(
                        youtubeId = t.youtubeId,
                        title = t.title,
                        artist = t.artist,
                        durationSeconds = t.durationSeconds,
                        thumbnailUrl = t.thumbnailUrl
                    )
                }
                SpotifyApplication.instance.playbackManager.playQueue(trackDtos, position)
            },
            onDeleteClick = { track ->
                showDeleteDialog(track)
            }
        )

        binding.recyclerDownloads.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = downloadAdapter
        }
    }

    private fun setupListeners() {
        binding.btnCreateOfflinePlaylist.setOnClickListener {
            showCreateOfflinePlaylistDialog()
        }
    }

    private fun showCreateOfflinePlaylistDialog() {
        val input = EditText(requireContext()).apply {
            hint = "Offline Playlist Title"
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Create Offline Playlist")
            .setMessage("Offline playlists are saved strictly in your local device database.")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        SpotifyApplication.instance.database.playlistDao().insert(
                            LocalPlaylist(title = name, isOfflineOnly = true)
                        )
                        activity?.runOnUiThread {
                            Toast.makeText(context, "Offline playlist \"$name\" created!", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteDialog(track: DownloadedTrack) {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete Download")
            .setMessage("Delete \"${track.title}\" from offline storage?")
            .setPositiveButton("Delete") { _, _ ->
                SpotifyApplication.instance.downloadHelper.deleteDownloadedTrack(track.youtubeId) {
                    activity?.runOnUiThread {
                        Toast.makeText(context, "Deleted from offline downloads", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun observeDownloadedTracks() {
        lifecycleScope.launch {
            SpotifyApplication.instance.database.downloadedTrackDao()
                .getAllDownloadedTracks()
                .collectLatest { tracks ->
                    downloadedTracks.clear()
                    downloadedTracks.addAll(tracks)
                    downloadAdapter.updateTracks(downloadedTracks)

                    if (tracks.isEmpty()) {
                        binding.textEmptyDownloads.visibility = View.VISIBLE
                        binding.textDownloadsSubtitle.text = "0 offline songs"
                    } else {
                        binding.textEmptyDownloads.visibility = View.GONE
                        binding.textDownloadsSubtitle.text = "${tracks.size} offline songs ready for playback"
                    }
                }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
