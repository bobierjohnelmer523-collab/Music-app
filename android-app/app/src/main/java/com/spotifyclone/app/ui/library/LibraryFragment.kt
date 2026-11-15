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
import com.spotifyclone.app.R
import com.spotifyclone.app.SpotifyApplication
import com.spotifyclone.app.api.ApiClient
import com.spotifyclone.app.data.local.entities.LocalPlaylist
import com.spotifyclone.app.databinding.FragmentLibraryBinding
import com.spotifyclone.app.model.PlaylistDto
import com.spotifyclone.app.model.PlaylistRequest
import com.spotifyclone.app.ui.adapter.PlaylistAdapter
import com.spotifyclone.app.ui.auth.AuthActivity
import com.spotifyclone.app.ui.downloads.DownloadsFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
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
        observeLikedSongsCount()
        observeDownloadsCount()
        loadLocalPlaylistsThenSync()
    }

    override fun onResume() {
        super.onResume()
        updateAccountStatus()
        syncPlaylistsWithBackend()
    }

    private fun updateAccountStatus() {
        if (ApiClient.isLoggedIn()) {
            val username = ApiClient.getUsername() ?: "User"
            binding.textAccountStatus.text = "Logged in as $username (Render Cloud Synced)"
            binding.btnLoginSignup.text = "Account"
            binding.btnLoginSignup.setOnClickListener {
                showAccountDialog(username)
            }
            binding.cardAuthBanner.setOnClickListener {
                showAccountDialog(username)
            }
        } else {
            binding.textAccountStatus.text = "Sign in to sync playlists with Render"
            binding.btnLoginSignup.text = "Log In"
            val openAuth = View.OnClickListener {
                startActivity(Intent(requireContext(), AuthActivity::class.java))
            }
            binding.btnLoginSignup.setOnClickListener(openAuth)
            binding.cardAuthBanner.setOnClickListener(openAuth)
        }
    }

    private fun showAccountDialog(username: String) {
        AlertDialog.Builder(requireContext())
            .setTitle("User Account")
            .setMessage("Logged in as: $username\nCloud Database: Neon PostgreSQL (Render)\nPlaylists automatically backed up.")
            .setPositiveButton("Log Out") { _, _ ->
                ApiClient.clearAuth()
                updateAccountStatus()
                Toast.makeText(context, "Logged out", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun observeLikedSongsCount() {
        lifecycleScope.launch {
            SpotifyApplication.instance.database.likedSongDao()
                .getLikedCount()
                .collectLatest { count ->
                    binding.textLikedSongsCount.text = "Playlist • $count ${if (count == 1) "song" else "songs"}"
                }
        }
    }

    private fun observeDownloadsCount() {
        lifecycleScope.launch {
            SpotifyApplication.instance.database.downloadedTrackDao()
                .getAllDownloadedTracks()
                .collectLatest { list ->
                    val count = list.size
                    binding.textDownloadedCount.text = "Playlist • $count ${if (count == 1) "song" else "songs"}"
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
            },
            onDownloadClick = { playlist ->
                if (playlist.tracks.isEmpty()) {
                    Toast.makeText(context, "Playlist has no tracks to download", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Downloading \"${playlist.title}\" (${playlist.tracks.size} songs) for offline playback...", Toast.LENGTH_LONG).show()
                    for (track in playlist.tracks) {
                        SpotifyApplication.instance.downloadHelper.downloadTrack(track)
                    }
                }
            }
        )

        binding.recyclerPlaylists.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = playlistAdapter
        }
    }

    private fun setupListeners() {
        // Pre-built Liked Songs opens LikedSongsFragment
        binding.cardLikedSongsPlaylist.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, LikedSongsFragment())
                .addToBackStack(null)
                .commit()
        }

        // Downloaded Songs playlist card opens DownloadsFragment
        binding.cardDownloadedPlaylist.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, DownloadsFragment())
                .addToBackStack(null)
                .commit()
        }

        binding.btnCreatePlaylist.setOnClickListener {
            showCreatePlaylistDialog()
        }

        binding.btnSyncPlaylists.setOnClickListener {
            syncPlaylistsWithBackend()
        }
    }

    private fun showCreatePlaylistDialog() {
        com.spotifyclone.app.ui.dialog.CustomDialogHelper.showInputDialog(
            context = requireContext(),
            title = "New Playlist",
            message = "Give your playlist a name.",
            hint = "My Playlist",
            confirmText = "Create"
        ) { title ->
            createPlaylist(title)
        }
    }

    private fun createPlaylist(title: String) {
        binding.progressLibrary.visibility = View.VISIBLE

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (ApiClient.isLoggedIn()) {
                    val response = ApiClient.getApi().createPlaylist(PlaylistRequest(title))
                    if (response.isSuccessful && response.body() != null) {
                        val created = response.body()!!
                        SpotifyApplication.instance.database.playlistDao().insert(
                            LocalPlaylist(remoteId = created.id, title = created.title, description = created.description)
                        )
                    }
                } else {
                    SpotifyApplication.instance.database.playlistDao().insert(
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
        com.spotifyclone.app.ui.dialog.CustomDialogHelper.showConfirmDialog(
            context = requireContext(),
            title = "Delete Playlist",
            message = "Are you sure you want to delete \"${playlist.title}\"?",
            confirmText = "Delete",
            isDestructive = true
        ) {
            deletePlaylist(playlist)
        }
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
