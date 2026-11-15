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
import com.spotifyclone.app.data.local.entities.LocalPlaylist
import com.spotifyclone.app.databinding.FragmentDownloadsBinding
import com.spotifyclone.app.model.TrackDto
import com.spotifyclone.app.ui.adapter.DownloadItem
import com.spotifyclone.app.ui.adapter.DownloadedTrackAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class DownloadsFragment : Fragment() {

    private var _binding: FragmentDownloadsBinding? = null
    private val binding get() = _binding!!

    private lateinit var downloadAdapter: DownloadedTrackAdapter
    private val displayItems = mutableListOf<DownloadItem>()

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
        observeDownloadedAndActiveTracks()
    }

    private fun setupRecyclerView() {
        downloadAdapter = DownloadedTrackAdapter(
            tracks = displayItems,
            onTrackClick = { item, _ ->
                val completed = displayItems.filter { !it.isDownloading }
                val targetIndex = completed.indexOfFirst { it.youtubeId == item.youtubeId }.coerceAtLeast(0)
                val trackDtos = completed.map { t ->
                    TrackDto(
                        youtubeId = t.youtubeId,
                        title = t.title,
                        artist = t.artist,
                        durationSeconds = t.durationSeconds,
                        thumbnailUrl = t.thumbnailUrl
                    )
                }
                SpotifyApplication.instance.playbackManager.playQueue(trackDtos, targetIndex)
            },
            onDeleteClick = { item ->
                showDeleteDialog(item)
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

    private fun showDeleteDialog(item: DownloadItem) {
        val title = if (item.isDownloading) "Cancel Download" else "Delete Download"
        val message = if (item.isDownloading) "Cancel download for \"${item.title}\"?" else "Delete \"${item.title}\" from offline storage?"

        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Confirm") { _, _ ->
                SpotifyApplication.instance.downloadHelper.deleteDownloadedTrack(item.youtubeId) {
                    activity?.runOnUiThread {
                        Toast.makeText(context, "Removed from offline downloads", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun observeDownloadedAndActiveTracks() {
        val downloadHelper = SpotifyApplication.instance.downloadHelper
        val db = SpotifyApplication.instance.database

        lifecycleScope.launch {
            combine(
                db.downloadedTrackDao().getAllDownloadedTracks(),
                downloadHelper.downloadingTracks,
                downloadHelper.downloadProgress
            ) { downloaded, downloading, progressMap ->
                val items = mutableListOf<DownloadItem>()

                // 1. Add actively downloading tracks at the top with live progress
                for (d in downloading) {
                    val percent = progressMap[d.youtubeId] ?: 0
                    items.add(
                        DownloadItem(
                            youtubeId = d.youtubeId,
                            title = d.title,
                            artist = d.artist,
                            thumbnailUrl = d.thumbnailUrl,
                            durationSeconds = d.durationSeconds,
                            isDownloading = true,
                            progressPercent = percent
                        )
                    )
                }

                // 2. Add completed downloaded tracks
                for (t in downloaded) {
                    if (items.none { it.youtubeId == t.youtubeId }) {
                        items.add(
                            DownloadItem(
                                youtubeId = t.youtubeId,
                                title = t.title,
                                artist = t.artist,
                                thumbnailUrl = t.thumbnailUrl,
                                durationSeconds = t.durationSeconds,
                                isDownloading = false,
                                progressPercent = 100
                            )
                        )
                    }
                }

                items
            }.collectLatest { items ->
                displayItems.clear()
                displayItems.addAll(items)
                downloadAdapter.updateTracks(displayItems)

                val completedCount = items.count { !it.isDownloading }
                val downloadingCount = items.count { it.isDownloading }

                if (items.isEmpty()) {
                    binding.textEmptyDownloads.visibility = View.VISIBLE
                    binding.textDownloadsSubtitle.text = "0 offline songs"
                } else {
                    binding.textEmptyDownloads.visibility = View.GONE
                    val statusText = if (downloadingCount > 0) {
                        "$completedCount offline • $downloadingCount downloading"
                    } else {
                        "$completedCount offline songs ready for playback"
                    }
                    binding.textDownloadsSubtitle.text = statusText
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
