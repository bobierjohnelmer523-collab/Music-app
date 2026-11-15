package com.spotifyclone.app.ui.library

import android.app.AlertDialog
import android.content.Intent
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
import com.spotifyclone.app.api.ApiClient
import com.spotifyclone.app.data.local.entities.LocalPlaylist
import com.spotifyclone.app.databinding.FragmentLibraryBinding
import com.spotifyclone.app.model.PlaylistDto
import com.spotifyclone.app.model.PlaylistRequest
import com.spotifyclone.app.ui.adapter.PlaylistAdapter
import com.spotifyclone.app.ui.auth.AuthActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LibraryFragment : Fragment() {

    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!

    private lateinit var playlistAdapter: PlaylistAdapter
    private val playlists = mutableListOf<PlaylistDto>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ApiClient.init(requireContext())
        setupRecyclerView()
        setupListeners()
        updateAccountStatus()
        loadLocalPlaylistsThenSync()
    }

    override fun onResume() {
        super.onResume()
        updateAccountStatus()
        syncPlaylistsWithBackend()
    }

    private fun updateAccountStatus() {
        if (ApiClient.isLoggedIn()) {
            binding.textAccountStatus.text = "Logged in as: ${ApiClient.getUsername()} (Render Cloud Synced)"
            binding.textAccountStatus.setOnClickListener(null)
        } else {
            binding.textAccountStatus.text = "Not logged in. Tap here to login & sync with Render backend."
            binding.textAccountStatus.setOnClickListener {
                startActivity(Intent(requireContext(), AuthActivity::class.java))
            }
        }
    }

    private fun setupRecyclerView() {
        playlistAdapter = PlaylistAdapter(
            playlists = playlists,
            onPlaylistClick = { playlist ->
                if (playlist.tracks.isNotEmpty()) {
                    SpotifyApplication.instance.playbackManager.playQueue(playlist.tracks)
                } else {
                    Toast.makeText(context, "Playlist is empty", Toast.LENGTH_SHORT).show()
                }
            },
            onDeleteClick = { playlist ->
                showDeleteDialog(playlist)
            }
        )

        binding.recyclerPlaylists.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = playlistAdapter
        }
    }

    private fun setupListeners() {
        binding.btnCreatePlaylist.setOnClickListener {
            showCreatePlaylistDialog()
        }

        binding.btnSyncPlaylists.setOnClickListener {
            syncPlaylistsWithBackend()
        }
    }

    private fun showCreatePlaylistDialog() {
        val input = EditText(requireContext()).apply {
            hint = "Playlist Name"
        }

        AlertDialog.Builder(requireContext())
            .setTitle("New Playlist")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val title = input.text.toString().trim()
                if (title.isNotEmpty()) {
                    createPlaylist(title)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun createPlaylist(title: String) {
        binding.progressLibrary.visibility = View.VISIBLE

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (ApiClient.isLoggedIn()) {
                    val response = ApiClient.getApi().createPlaylist(PlaylistRequest(title))
                    if (response.isSuccessful && response.body() != null) {
                        val created = response.body()!!
                        // Save in local Room
                        SpotifyApplication.instance.database.playlistDao().insert(
                            LocalPlaylist(remoteId = created.id, title = created.title, description = created.description)
                        )
                    }
                } else {
                    // Local only
                    val localId = SpotifyApplication.instance.database.playlistDao().insert(
                        LocalPlaylist(title = title, isOfflineOnly = true)
                    )
                }

                syncPlaylistsWithBackend()

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Failed to create: ${e.message}", Toast.LENGTH_SHORT).show()
                    binding.progressLibrary.visibility = View.GONE
                }
            }
        }
    }

    private fun showDeleteDialog(playlist: PlaylistDto) {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete Playlist")
            .setMessage("Delete \"${playlist.title}\"?")
            .setPositiveButton("Delete") { _, _ ->
                deletePlaylist(playlist)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deletePlaylist(playlist: PlaylistDto) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (ApiClient.isLoggedIn() && playlist.id > 0) {
                    ApiClient.getApi().deletePlaylist(playlist.id)
                }
                val local = SpotifyApplication.instance.database.playlistDao().getPlaylistByRemoteId(playlist.id)
                if (local != null) {
                    SpotifyApplication.instance.database.playlistDao().delete(local)
                }
                syncPlaylistsWithBackend()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Error deleting playlist: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun loadLocalPlaylistsThenSync() {
        lifecycleScope.launch(Dispatchers.IO) {
            val localList = SpotifyApplication.instance.database.playlistDao().getAllPlaylists().first()
            val dtos = localList.map { p ->
                val tracks = SpotifyApplication.instance.database.trackDao().getTracksForPlaylistSync(p.id)
                PlaylistDto(
                    id = p.remoteId ?: p.id,
                    title = p.title,
                    description = p.description,
                    createdAt = null,
                    updatedAt = null,
                    tracks = tracks.map { t ->
                        com.spotifyclone.app.model.TrackDto(
                            id = t.id,
                            youtubeId = t.youtubeId,
                            title = t.title,
                            artist = t.artist,
                            durationSeconds = t.durationSeconds,
                            thumbnailUrl = t.thumbnailUrl,
                            position = t.position
                        )
                    }
                )
            }

            withContext(Dispatchers.Main) {
                playlists.clear()
                playlists.addAll(dtos)
                playlistAdapter.updatePlaylists(playlists)
            }

            syncPlaylistsWithBackend()
        }
    }

    private fun syncPlaylistsWithBackend() {
        if (!ApiClient.isLoggedIn()) return
        binding.progressLibrary.visibility = View.VISIBLE

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val response = ApiClient.getApi().getPlaylists()
                if (response.isSuccessful && response.body() != null) {
                    val remotePlaylists = response.body()!!

                    // Sync remote into Room DB
                    val db = SpotifyApplication.instance.database
                    for (remote in remotePlaylists) {
                        val existing = db.playlistDao().getPlaylistByRemoteId(remote.id)
                        val localId = if (existing == null) {
                            db.playlistDao().insert(
                                LocalPlaylist(remoteId = remote.id, title = remote.title, description = remote.description)
                            )
                        } else {
                            existing.id
                        }

                        // Re-cache tracks
                        db.trackDao().deleteTracksForPlaylist(localId)
                        val tracksToInsert = remote.tracks.map { tr ->
                            com.spotifyclone.app.data.local.entities.LocalTrack(
                                playlistId = localId,
                                youtubeId = tr.youtubeId,
                                title = tr.title,
                                artist = tr.artist,
                                durationSeconds = tr.durationSeconds,
                                thumbnailUrl = tr.thumbnailUrl,
                                position = tr.position ?: 0
                            )
                        }
                        db.trackDao().insertAll(tracksToInsert)
                    }

                    withContext(Dispatchers.Main) {
                        binding.progressLibrary.visibility = View.GONE
                        playlists.clear()
                        playlists.addAll(remotePlaylists)
                        playlistAdapter.updatePlaylists(playlists)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        binding.progressLibrary.visibility = View.GONE
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.progressLibrary.visibility = View.GONE
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
