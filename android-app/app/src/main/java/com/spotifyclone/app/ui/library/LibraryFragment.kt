package com.spotifyclone.app.ui.library

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.spotifyclone.app.R
import com.spotifyclone.app.SpotifyApplication
import com.spotifyclone.app.api.ApiClient
import com.spotifyclone.app.data.local.entities.LocalPlaylist
import com.spotifyclone.app.databinding.FragmentLibraryBinding
import com.spotifyclone.app.model.PlaylistDto
import com.spotifyclone.app.model.PlaylistRequest
import com.spotifyclone.app.ui.adapter.PlaylistAdapter
import com.spotifyclone.app.ui.auth.AuthActivity
import com.spotifyclone.app.ui.dialog.CustomDialogHelper
import com.spotifyclone.app.ui.downloads.DownloadsFragment
import com.spotifyclone.app.ui.playlist.PlaylistDetailFragment
import com.spotifyclone.app.ui.search.SearchFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LibraryFragment : Fragment() {

    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!

    private lateinit var playlistAdapter: PlaylistAdapter
    private val playlists = mutableListOf<PlaylistDto>()
    private var isGridView = false

    // Photo/GIF Picker for Playlist Creation
    private var pendingDialogPreview: ImageView? = null
    private var pendingCoverUri: String? = null

    private val pickMediaLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val uriString = it.toString()
            pendingCoverUri = uriString
            pendingDialogPreview?.let { img ->
                Glide.with(this).load(it).centerCrop().into(img)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ApiClient.init(requireContext())
        setupProfileAvatar()
        setupRecyclerView()
        setupListeners()
        setupFilterChips()
        observeLikedSongsCount()
        observeDownloadsCount()
        loadLocalPlaylistsThenSync()
    }

    override fun onResume() {
        super.onResume()
        setupProfileAvatar()
        loadLocalPlaylistsInternal()
        syncPlaylistsWithBackend()
    }

    private fun setupProfileAvatar() {
        if (ApiClient.isLoggedIn()) {
            val username = ApiClient.getUsername() ?: "User"
            val initial = username.firstOrNull()?.uppercaseChar()?.toString() ?: "U"
            binding.textAvatarInitial.text = initial
            binding.btnProfileAvatar.setOnClickListener {
                showAccountDialog(username)
            }
        } else {
            binding.textAvatarInitial.text = "👤"
            binding.btnProfileAvatar.setOnClickListener {
                startActivity(Intent(requireContext(), AuthActivity::class.java))
            }
        }
    }

    private fun showAccountDialog(username: String) {
        CustomDialogHelper.showConfirmDialog(
            context = requireContext(),
            title = "Account Settings",
            message = "Logged in as: $username\nCloud Database: Neon PostgreSQL (Render)\nStatus: Connected & Synchronized",
            confirmText = "Log Out",
            isDestructive = true
        ) {
            ApiClient.clearAuth()
            setupProfileAvatar()
            Toast.makeText(context, "Logged out successfully", Toast.LENGTH_SHORT).show()
        }
    }

    private fun observeLikedSongsCount() {
        lifecycleScope.launch {
            SpotifyApplication.instance.database.likedSongDao()
                .getLikedCount()
                .collectLatest { count ->
                    binding.textLikedSongsCount.text = "📌 Playlist • $count ${if (count == 1) "song" else "songs"}"
                }
        }
    }

    private fun observeDownloadsCount() {
        lifecycleScope.launch {
            SpotifyApplication.instance.database.downloadedTrackDao()
                .getAllDownloadedTracks()
                .collectLatest { list ->
                    val count = list.size
                    binding.textDownloadedCount.text = "📌 Playlist • $count offline ${if (count == 1) "song" else "songs"}"
                }
        }
    }

    private fun setupRecyclerView() {
        playlistAdapter = PlaylistAdapter(
            playlists = playlists,
            onPlaylistClick = { playlist ->
                // Open PlaylistDetailFragment instead of directly playing
                val fragment = PlaylistDetailFragment.newInstance(
                    id = playlist.id,
                    title = playlist.title,
                    description = playlist.description,
                    coverUrl = playlist.coverUrl
                )
                parentFragmentManager.beginTransaction()
                    .replace(R.id.fragment_container, fragment)
                    .addToBackStack(null)
                    .commit()
            },
            onOptionsClick = { playlist, _ ->
                showPlaylistOptionsMenu(playlist)
            },
            isGridView = isGridView
        )

        updateLayoutManager()
        binding.recyclerPlaylists.adapter = playlistAdapter
    }

    private fun updateLayoutManager() {
        binding.recyclerPlaylists.layoutManager = if (isGridView) {
            GridLayoutManager(context, 2)
        } else {
            LinearLayoutManager(context)
        }
        playlistAdapter.setGridView(isGridView)
        binding.btnToggleViewMode.setImageResource(
            if (isGridView) R.drawable.ic_list_view else R.drawable.ic_grid_view
        )
    }

    private fun setupListeners() {
        // Search Library icon
        binding.btnLibrarySearch.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, SearchFragment())
                .addToBackStack(null)
                .commit()
        }

        // Toggle List vs Grid View
        binding.btnToggleViewMode.setOnClickListener {
            isGridView = !isGridView
            updateLayoutManager()
        }

        // Sort Recents
        binding.layoutSortRecents.setOnClickListener {
            showSortMenu()
        }

        // Liked Songs card
        binding.cardLikedSongsPlaylist.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, LikedSongsFragment())
                .addToBackStack(null)
                .commit()
        }

        // Downloaded Songs card
        binding.cardDownloadedPlaylist.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, DownloadsFragment())
                .addToBackStack(null)
                .commit()
        }

        // Create Playlist button
        binding.btnCreatePlaylist.setOnClickListener {
            showCreatePlaylistDialog()
        }
    }

    private fun setupFilterChips() {
        val chips = listOf(
            binding.chipPlaylists,
            binding.chipPodcasts,
            binding.chipArtists,
            binding.chipDownloaded
        )

        for (chip in chips) {
            chip.setOnClickListener {
                for (c in chips) {
                    if (c == chip) {
                        c.setBackgroundResource(R.drawable.chip_selected_bg)
                        c.setTextColor(resources.getColor(R.color.spotify_black, null))
                    } else {
                        c.setBackgroundResource(R.drawable.chip_unselected_bg)
                        c.setTextColor(resources.getColor(R.color.spotify_white, null))
                    }
                }
                if (chip == binding.chipDownloaded) {
                    parentFragmentManager.beginTransaction()
                        .replace(R.id.fragment_container, DownloadsFragment())
                        .addToBackStack(null)
                        .commit()
                }
            }
        }
    }

    private fun showSortMenu() {
        val options = arrayOf("Recents", "Recently Added", "Alphabetical")
        AlertDialog.Builder(requireContext())
            .setTitle("Sort by")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        binding.textSortLabel.text = "Recents"
                        playlists.sortByDescending { it.updatedAt ?: "" }
                        playlistAdapter.updatePlaylists(playlists.toList())
                    }
                    1 -> {
                        binding.textSortLabel.text = "Recently Added"
                        playlists.sortByDescending { it.id }
                        playlistAdapter.updatePlaylists(playlists.toList())
                    }
                    2 -> {
                        binding.textSortLabel.text = "Alphabetical"
                        playlists.sortBy { it.title.lowercase() }
                        playlistAdapter.updatePlaylists(playlists.toList())
                    }
                }
            }
            .show()
    }

    private fun showCreatePlaylistDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_custom_playlist_editor, null)
        val textTitle = view.findViewById<TextView>(R.id.text_editor_dialog_title)
        val imgPreview = view.findViewById<ImageView>(R.id.img_editor_cover_preview)
        val btnPickFile = view.findViewById<TextView>(R.id.btn_pick_cover_file)
        val editCoverUrl = view.findViewById<EditText>(R.id.edit_playlist_cover_url)
        val editTitle = view.findViewById<EditText>(R.id.edit_playlist_title)
        val editDesc = view.findViewById<EditText>(R.id.edit_playlist_description)
        val btnCancel = view.findViewById<TextView>(R.id.btn_cancel_playlist_editor)
        val btnSave = view.findViewById<TextView>(R.id.btn_save_playlist_editor)

        textTitle.text = "New Playlist"
        btnSave.text = "Create"

        pendingDialogPreview = imgPreview
        pendingCoverUri = null

        btnPickFile.setOnClickListener {
            pickMediaLauncher.launch("image/*")
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setView(view)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnSave.setOnClickListener {
            val title = editTitle.text.toString().trim()
            val desc = editDesc.text.toString().trim().takeIf { it.isNotBlank() }
            val urlText = editCoverUrl.text.toString().trim().takeIf { it.isNotBlank() }
            val cover = pendingCoverUri ?: urlText

            if (title.isEmpty()) {
                Toast.makeText(context, "Give your playlist a name", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            dialog.dismiss()
            createPlaylist(title, desc, cover)
        }

        dialog.show()
    }

    private fun createPlaylist(title: String, description: String?, coverUrl: String?) {
        binding.progressLibrary.visibility = View.VISIBLE

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (ApiClient.isLoggedIn()) {
                    val response = ApiClient.getApi().createPlaylist(
                        PlaylistRequest(title = title, description = description, coverUrl = coverUrl)
                    )
                    if (response.isSuccessful && response.body() != null) {
                        val created = response.body()!!
                        SpotifyApplication.instance.database.playlistDao().insert(
                            LocalPlaylist(
                                remoteId = created.id,
                                title = created.title,
                                description = created.description,
                                coverUrl = created.coverUrl ?: coverUrl
                            )
                        )
                    } else {
                        SpotifyApplication.instance.database.playlistDao().insert(
                            LocalPlaylist(title = title, description = description, coverUrl = coverUrl, isOfflineOnly = true)
                        )
                    }
                } else {
                    SpotifyApplication.instance.database.playlistDao().insert(
                        LocalPlaylist(title = title, description = description, coverUrl = coverUrl, isOfflineOnly = true)
                    )
                }

                loadLocalPlaylistsInternal()
                syncPlaylistsWithBackend()

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Failed to create: ${e.message}", Toast.LENGTH_SHORT).show()
                    binding.progressLibrary.visibility = View.GONE
                }
            }
        }
    }

    private fun showPlaylistOptionsMenu(playlist: PlaylistDto) {
        val options = arrayOf(
            "Open Playlist",
            "Download All Songs",
            "Delete Playlist"
        )

        AlertDialog.Builder(requireContext())
            .setTitle(playlist.title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        val fragment = PlaylistDetailFragment.newInstance(
                            id = playlist.id,
                            title = playlist.title,
                            description = playlist.description,
                            coverUrl = playlist.coverUrl
                        )
                        parentFragmentManager.beginTransaction()
                            .replace(R.id.fragment_container, fragment)
                            .addToBackStack(null)
                            .commit()
                    }
                    1 -> {
                        if (playlist.tracks.isNotEmpty()) {
                            Toast.makeText(context, "Downloading ${playlist.tracks.size} songs...", Toast.LENGTH_SHORT).show()
                            for (t in playlist.tracks) {
                                SpotifyApplication.instance.downloadHelper.downloadTrack(t)
                            }
                        } else {
                            Toast.makeText(context, "Playlist is empty", Toast.LENGTH_SHORT).show()
                        }
                    }
                    2 -> {
                        showDeleteDialog(playlist)
                    }
                }
            }
            .show()
    }

    private fun showDeleteDialog(playlist: PlaylistDto) {
        CustomDialogHelper.showConfirmDialog(
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
        // Immediate optimistic removal from screen
        val itemIndex = playlists.indexOfFirst { it.id == playlist.id || it.title == playlist.title }
        if (itemIndex != -1) {
            playlists.removeAt(itemIndex)
            playlistAdapter.updatePlaylists(playlists.toList())
        }
        Toast.makeText(context, "Playlist \"${playlist.title}\" deleted", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = SpotifyApplication.instance.database
                val local = db.playlistDao().getPlaylistByRemoteId(playlist.id)
                    ?: db.playlistDao().getPlaylistById(playlist.id)
                    ?: db.playlistDao().getPlaylistByTitle(playlist.title)

                val remoteIdToDelete = local?.remoteId ?: if (playlist.id > 0) playlist.id else null

                if (ApiClient.isLoggedIn() && remoteIdToDelete != null) {
                    try {
                        ApiClient.getApi().deletePlaylist(remoteIdToDelete)
                    } catch (_: Exception) {}
                }

                if (local != null) {
                    db.trackDao().deleteTracksForPlaylist(local.id)
                    db.playlistDao().delete(local)
                } else {
                    db.playlistDao().deleteByIdOrRemoteIdOrTitle(playlist.id, playlist.title)
                }

                loadLocalPlaylistsInternal()
                syncPlaylistsWithBackend()

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Error deleting playlist: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun loadLocalPlaylistsInternal() {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = SpotifyApplication.instance.database
            val localList = db.playlistDao().getAllPlaylistsOnce()
            val dtos = localList.map { p ->
                val tracks = db.trackDao().getTracksForPlaylistSync(p.id)
                PlaylistDto(
                    id = p.remoteId ?: p.id,
                    title = p.title,
                    description = p.description,
                    coverUrl = p.coverUrl,
                    createdAt = null,
                    updatedAt = p.updatedAt.toString(),
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
                binding.progressLibrary.visibility = View.GONE
                playlists.clear()
                playlists.addAll(dtos)
                playlistAdapter.updatePlaylists(playlists.toList())

                if (playlists.isEmpty()) {
                    binding.textEmptyLibrary.visibility = View.VISIBLE
                } else {
                    binding.textEmptyLibrary.visibility = View.GONE
                }
            }
        }
    }

    private fun loadLocalPlaylistsThenSync() {
        loadLocalPlaylistsInternal()
        syncPlaylistsWithBackend()
    }

    private fun syncPlaylistsWithBackend() {
        if (!ApiClient.isLoggedIn()) return

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val response = ApiClient.getApi().getPlaylists()
                if (response.isSuccessful && response.body() != null) {
                    val remotePlaylists = response.body()!!
                    val remoteIds = remotePlaylists.map { it.id }.toSet()

                    val db = SpotifyApplication.instance.database

                    // Prune local playlists that were deleted on the remote server
                    val allLocal = db.playlistDao().getAllPlaylistsOnce()
                    for (localP in allLocal) {
                        if (localP.remoteId != null && !remoteIds.contains(localP.remoteId)) {
                            db.trackDao().deleteTracksForPlaylist(localP.id)
                            db.playlistDao().delete(localP)
                        }
                    }

                    // Upsert remote playlists and their tracks
                    for (remote in remotePlaylists) {
                        val existing = db.playlistDao().getPlaylistByRemoteId(remote.id)
                        val localId = if (existing == null) {
                            db.playlistDao().insert(
                                LocalPlaylist(
                                    remoteId = remote.id,
                                    title = remote.title,
                                    description = remote.description,
                                    coverUrl = remote.coverUrl
                                )
                            )
                        } else {
                            if (remote.coverUrl != null && existing.coverUrl == null) {
                                db.playlistDao().updateCoverUrl(existing.id, remote.coverUrl)
                            }
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

                    loadLocalPlaylistsInternal()
                }
            } catch (_: Exception) {}
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
