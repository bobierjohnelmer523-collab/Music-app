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
import com.spotifyclone.app.model.SpotifyPlaylistSummary
import com.spotifyclone.app.spotify.SpotifyAuthManager
import com.spotifyclone.app.spotify.SpotifyImportStore
import com.spotifyclone.app.spotify.SpotifyRepository
import com.spotifyclone.app.spotify.coverUrl
import com.spotifyclone.app.ui.adapter.LibraryItem
import com.spotifyclone.app.ui.adapter.LibraryItemType
import com.spotifyclone.app.ui.adapter.PlaylistAdapter
import com.spotifyclone.app.ui.auth.AuthActivity
import com.spotifyclone.app.ui.dialog.CustomDialogHelper
import com.spotifyclone.app.ui.downloads.DownloadsFragment
import com.spotifyclone.app.ui.playlist.PlaylistDetailFragment
import com.spotifyclone.app.ui.playlist.SpotifyPlaylistDetailFragment
import com.spotifyclone.app.ui.search.SearchFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LibraryFragment : Fragment() {

    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!

    private lateinit var playlistAdapter: PlaylistAdapter
    private val libraryItems = mutableListOf<LibraryItem>()
    private val userPlaylists = mutableListOf<PlaylistDto>()
    private val spotifyPlaylists = mutableListOf<SpotifyPlaylistSummary>()

    private var likedCount: Int = 0
    private var downloadedCount: Int = 0
    private var isGridView = false

    // Photo/GIF Picker for Playlist Creation
    private var pendingDialogPreview: ImageView? = null
    private var pendingBackgroundPreview: ImageView? = null
    private var pendingCoverUri: String? = null
    private var pendingBackgroundUri: String? = null

    private val pickMediaLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            runCatching {
                requireContext().contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            val uriString = it.toString()
            pendingCoverUri = uriString
            pendingDialogPreview?.let { img ->
                img.imageTintList = null
                img.clearColorFilter()
                Glide.with(this).load(it).centerCrop().into(img)
            }
        }
    }

    private val pickBackgroundLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            runCatching {
                requireContext().contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            pendingBackgroundUri = it.toString()
            pendingBackgroundPreview?.let { image ->
                Glide.with(this).load(it).centerCrop().into(image)
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
        SpotifyAuthManager.init(requireContext())
        setupProfileAvatar()
        setupRecyclerView()
        setupListeners()
        setupFilterChips()
        observeLikedSongsCount()
        observeDownloadsCount()
        loadLocalPlaylistsThenSync()
        loadSpotifyPlaylists()
    }

    override fun onResume() {
        super.onResume()
        setupProfileAvatar()
        loadLocalPlaylistsInternal()
        syncPlaylistsWithBackend()
        loadSpotifyPlaylists()
    }

    private fun setupProfileAvatar() {
        if (ApiClient.isLoggedIn()) {
            val username = ApiClient.getUsername() ?: "User"
            val initial = username.firstOrNull()?.uppercaseChar()?.toString() ?: "U"
            binding.textAvatarInitial.visibility = View.VISIBLE
            binding.imgAvatarIcon.visibility = View.GONE
            binding.textAvatarInitial.text = initial
            binding.btnProfileAvatar.setOnClickListener {
                showAccountDialog(username)
            }
        } else {
            binding.textAvatarInitial.visibility = View.GONE
            binding.imgAvatarIcon.visibility = View.VISIBLE
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

    private fun showImportDialog() {
        val input = android.widget.EditText(requireContext()).apply {
            hint = "Paste Spotify playlist link (open.spotify.com/playlist/...)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Import Spotify Playlist")
            .setMessage("Paste a public Spotify playlist link to add it to Your Library.")
            .setView(input)
            .setPositiveButton("Import") { _, _ ->
                val url = input.text.toString().trim()
                val playlistId = extractPlaylistId(url)
                if (playlistId == null) {
                    Toast.makeText(
                        context,
                        "Invalid Spotify playlist link",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setPositiveButton
                }

                // Fetch metadata and save
                binding.progressLibrary.visibility = View.VISIBLE
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    val meta = SpotifyRepository.getPlaylistMeta(playlistId)
                    withContext(Dispatchers.Main) {
                        binding.progressLibrary.visibility = View.GONE
                        if (meta == null) {
                            Toast.makeText(
                                context,
                                "Couldn't load that playlist — it may be private",
                                Toast.LENGTH_LONG
                            ).show()
                            return@withContext
                        }

                        SpotifyImportStore.add(
                            requireContext(),
                            SpotifyImportStore.ImportedPlaylist(
                                id = playlistId,
                                title = meta.title,
                                coverUrl = meta.coverUrl,
                                addedAt = System.currentTimeMillis()
                            )
                        )

                        val fragment = SpotifyPlaylistDetailFragment.forPlaylist(
                            playlistId = playlistId,
                            title = meta.title,
                            description = meta.description,
                            coverUrl = meta.coverUrl
                        )
                        parentFragmentManager.beginTransaction()
                            .replace(R.id.fragment_container, fragment)
                            .addToBackStack(null)
                            .commit()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun extractPlaylistId(url: String): String? {
        val pattern = """(?:open\.spotify\.com/playlist/|spotify:playlist:)([a-zA-Z0-9]+)""".toRegex()
        return pattern.find(url)?.groupValues?.get(1)
    }

    private fun loadSpotifyPlaylists() {
        if (!SpotifyAuthManager.isLoggedIn()) {
            spotifyPlaylists.clear()
            rebuildLibraryItems()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val playlists = SpotifyRepository.getMyPlaylists()
            val user = SpotifyRepository.getCurrentUser()
            user?.displayName?.let { SpotifyAuthManager.saveDisplayName(it) }
            withContext(Dispatchers.Main) {
                if (_binding == null) return@withContext
                spotifyPlaylists.clear()
                spotifyPlaylists.addAll(playlists)
                rebuildLibraryItems()
            }
        }
    }

    private fun observeLikedSongsCount() {
        viewLifecycleOwner.lifecycleScope.launch {
            SpotifyApplication.instance.database.likedSongDao()
                .getLikedCount()
                .collectLatest { count ->
                    likedCount = count
                    if (_binding != null) rebuildLibraryItems()
                }
        }
    }

    private fun observeDownloadsCount() {
        viewLifecycleOwner.lifecycleScope.launch {
            SpotifyApplication.instance.database.downloadedTrackDao()
                .getAllDownloadedTracks()
                .collectLatest { list ->
                    downloadedCount = list.size
                    if (_binding != null) rebuildLibraryItems()
                }
        }
    }

    private fun setupRecyclerView() {
        playlistAdapter = PlaylistAdapter(
            items = libraryItems,
            onItemClick = { item ->
                when (item.type) {
                    LibraryItemType.LIKED_SONGS -> {
                        parentFragmentManager.beginTransaction()
                            .replace(R.id.fragment_container, LikedSongsFragment())
                            .addToBackStack(null)
                            .commit()
                    }
                    LibraryItemType.DOWNLOADED_SONGS -> {
                        parentFragmentManager.beginTransaction()
                            .replace(R.id.fragment_container, DownloadsFragment())
                            .addToBackStack(null)
                            .commit()
                    }
                    LibraryItemType.CUSTOM_PLAYLIST -> {
                        item.playlistDto?.let { playlist ->
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
                    }
                    LibraryItemType.SPOTIFY_PLAYLIST -> {
                        item.spotifyPlaylistId?.let { spotifyId ->
                            val fragment = SpotifyPlaylistDetailFragment.forPlaylist(
                                playlistId = spotifyId,
                                title = item.title,
                                description = null,
                                coverUrl = item.coverUrl
                            )
                            parentFragmentManager.beginTransaction()
                                .replace(R.id.fragment_container, fragment)
                                .addToBackStack(null)
                                .commit()
                        }
                    }
                    LibraryItemType.CONNECT_SPOTIFY -> {
                        showImportDialog()
                    }
                }
            },
            onOptionsClick = { playlist, _ ->
                if (playlist.type == LibraryItemType.SPOTIFY_PLAYLIST) {
                    showImportedSpotifyPlaylistOptions(playlist)
                } else {
                    playlist.playlistDto?.let(::showPlaylistOptionsMenu)
                }
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

    private fun rebuildLibraryItems() {
        val items = mutableListOf<LibraryItem>()

        // 1. Liked Songs (Follows current List or Grid view)
        items.add(
            LibraryItem(
                type = LibraryItemType.LIKED_SONGS,
                title = "Liked Songs",
                details = "Playlist • $likedCount ${if (likedCount == 1) "song" else "songs"}"
            )
        )

        // 2. Downloaded Songs (Follows current List or Grid view)
        items.add(
            LibraryItem(
                type = LibraryItemType.DOWNLOADED_SONGS,
                title = "Downloaded Songs",
                details = "Playlist • $downloadedCount offline ${if (downloadedCount == 1) "song" else "songs"}"
            )
        )

        // 3. Custom Playlists
        for (p in userPlaylists) {
            val count = p.tracks.size
            items.add(
                LibraryItem(
                    type = LibraryItemType.CUSTOM_PLAYLIST,
                    title = p.title,
                    details = "Playlist • $count ${if (count == 1) "song" else "songs"}",
                    coverUrl = p.coverUrl,
                    playlistDto = p
                )
            )
        }

        // 4. Spotify: account playlists (if connected) + locally imported playlists
        val apiPlaylistIds = spotifyPlaylists.map { it.id }.toSet()
        if (SpotifyAuthManager.isLoggedIn()) {
            for (sp in spotifyPlaylists) {
                val count = sp.tracks?.total
                items.add(
                    LibraryItem(
                        type = LibraryItemType.SPOTIFY_PLAYLIST,
                        title = sp.name,
                        details = "Spotify Playlist" +
                            (count?.let { " • $it ${if (it == 1) "song" else "songs"}" } ?: ""),
                        coverUrl = sp.coverUrl(),
                        spotifyPlaylistId = sp.id
                    )
                )
            }
        }

        for (imp in SpotifyImportStore.getAll(requireContext())) {
            if (imp.id in apiPlaylistIds) continue
            items.add(
                LibraryItem(
                    type = LibraryItemType.SPOTIFY_PLAYLIST,
                    title = imp.title.ifBlank { "Spotify Playlist" },
                    details = "Spotify Playlist • Imported",
                    coverUrl = imp.coverUrl,
                    spotifyPlaylistId = imp.id,
                    isImportedSpotifyPlaylist = true
                )
            )
        }

        libraryItems.clear()
        libraryItems.addAll(items)
        playlistAdapter.updateItems(libraryItems)

        binding.textEmptyLibrary.visibility = View.GONE
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

        // Create Playlist button
        binding.btnCreatePlaylist.setOnClickListener {
            showAddLibraryMenu()
        }
    }

    private fun showAddLibraryMenu() {
        AlertDialog.Builder(requireContext())
            .setItems(arrayOf("Create Playlist", "Import Spotify Playlist")) { _, which ->
                when (which) {
                    0 -> showCreatePlaylistDialog()
                    1 -> showImportDialog()
                }
            }
            .show()
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
                        userPlaylists.sortByDescending { it.updatedAt ?: "" }
                        rebuildLibraryItems()
                    }
                    1 -> {
                        binding.textSortLabel.text = "Recently Added"
                        userPlaylists.sortByDescending { it.id }
                        rebuildLibraryItems()
                    }
                    2 -> {
                        binding.textSortLabel.text = "Alphabetical"
                        userPlaylists.sortBy { it.title.lowercase() }
                        rebuildLibraryItems()
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
        val imgBackgroundPreview = view.findViewById<ImageView>(R.id.img_editor_background_preview)
        val btnPickBackground = view.findViewById<TextView>(R.id.btn_pick_background_file)
        val editCoverUrl = view.findViewById<EditText>(R.id.edit_playlist_cover_url)
        val editBackgroundUrl = view.findViewById<EditText>(R.id.edit_playlist_background_url)
        val editTitle = view.findViewById<EditText>(R.id.edit_playlist_title)
        val editDesc = view.findViewById<EditText>(R.id.edit_playlist_description)
        val btnCancel = view.findViewById<TextView>(R.id.btn_cancel_playlist_editor)
        val btnSave = view.findViewById<TextView>(R.id.btn_save_playlist_editor)

        textTitle.text = "New Playlist"
        btnSave.text = "Create"

        pendingDialogPreview = imgPreview
        pendingBackgroundPreview = imgBackgroundPreview
        pendingCoverUri = null
        pendingBackgroundUri = null

        btnPickFile.setOnClickListener {
            pickMediaLauncher.launch(arrayOf("image/*"))
        }
        btnPickBackground.setOnClickListener {
            pickBackgroundLauncher.launch(arrayOf("image/*"))
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
            val backgroundText = editBackgroundUrl.text.toString().trim().takeIf { it.isNotBlank() }
            val background = pendingBackgroundUri ?: backgroundText

            if (title.isEmpty()) {
                Toast.makeText(context, "Give your playlist a name", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            dialog.dismiss()
            createPlaylist(title, desc, cover, background)
        }

        dialog.show()
    }

    private fun createPlaylist(
        title: String,
        description: String?,
        coverUrl: String?,
        backgroundUrl: String?
    ) {
        binding.progressLibrary.visibility = View.VISIBLE

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = SpotifyApplication.instance.database
                // Save the playlist locally first. A gallery URI is private to
                // this device and cannot be uploaded as a normal cover URL.
                val localId = db.playlistDao().insert(
                    LocalPlaylist(
                        title = title,
                        description = description,
                        coverUrl = coverUrl,
                        isOfflineOnly = !ApiClient.isLoggedIn()
                    )
                )
                var savedPlaylistId = localId

                if (ApiClient.isLoggedIn()) {
                    runCatching {
                        val serverCover = coverUrl?.takeIf {
                            it.startsWith("https://", ignoreCase = true) ||
                                it.startsWith("http://", ignoreCase = true)
                        }
                        ApiClient.getApi().createPlaylist(
                            PlaylistRequest(title = title, description = description, coverUrl = serverCover)
                        )
                    }.getOrNull()?.let { response ->
                        response.body()?.takeIf { response.isSuccessful }?.let { created ->
                            val local = db.playlistDao().getPlaylistById(localId)
                            if (local != null) {
                                db.playlistDao().update(
                                    local.copy(
                                        remoteId = created.id,
                                        title = created.title,
                                        description = created.description,
                                        coverUrl = created.coverUrl ?: coverUrl,
                                        isOfflineOnly = false
                                    )
                                )
                                savedPlaylistId = created.id
                            }
                        }
                    }
                }

                if (!backgroundUrl.isNullOrBlank()) {
                    SpotifyApplication.instance.getSharedPreferences(
                        "playlist_detail_preferences",
                        android.content.Context.MODE_PRIVATE
                    ).edit().putString("playlist_background_$savedPlaylistId", backgroundUrl).apply()
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

    private fun showImportedSpotifyPlaylistOptions(item: LibraryItem) {
        val playlistId = item.spotifyPlaylistId ?: return
        val options = arrayOf("Open Playlist", "Download All Songs", "Delete Playlist")
        AlertDialog.Builder(requireContext())
            .setTitle(item.title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openSpotifyPlaylist(item)
                    1 -> downloadSpotifyPlaylist(item)
                    2 -> AlertDialog.Builder(requireContext())
                        .setTitle("Delete Playlist")
                        .setMessage("Remove \"${item.title}\" from your library?")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Delete") { _, _ ->
                            SpotifyImportStore.remove(requireContext(), playlistId)
                            rebuildLibraryItems()
                        }
                        .show()
                }
            }
            .show()
    }

    private fun openSpotifyPlaylist(item: LibraryItem) {
        val playlistId = item.spotifyPlaylistId ?: return
        val fragment = SpotifyPlaylistDetailFragment.forPlaylist(
            playlistId = playlistId,
            title = item.title,
            description = null,
            coverUrl = item.coverUrl
        )
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .addToBackStack(null)
            .commit()
    }

    private fun downloadSpotifyPlaylist(item: LibraryItem) {
        val playlistId = item.spotifyPlaylistId ?: return
        binding.progressLibrary.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            val content = runCatching {
                withContext(Dispatchers.IO) {
                    SpotifyRepository.getPlaylistContent(playlistId)
                }
            }.getOrNull()
            val b = _binding ?: return@launch
            b.progressLibrary.visibility = View.GONE
            val tracks = content?.tracks.orEmpty()
            if (tracks.isEmpty()) {
                Toast.makeText(context, "Couldn't load playlist songs", Toast.LENGTH_SHORT).show()
                return@launch
            }
            tracks.forEach { SpotifyApplication.instance.downloadHelper.downloadTrack(it) }
            Toast.makeText(
                context,
                "Downloading ${tracks.size} songs for offline playback",
                Toast.LENGTH_LONG
            ).show()
        }
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
        val itemIndex = userPlaylists.indexOfFirst { it.id == playlist.id || it.title == playlist.title }
        if (itemIndex != -1) {
            userPlaylists.removeAt(itemIndex)
            rebuildLibraryItems()
        }
        Toast.makeText(context, "Playlist \"${playlist.title}\" deleted", Toast.LENGTH_SHORT).show()

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
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
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
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
                userPlaylists.clear()
                userPlaylists.addAll(dtos)
                rebuildLibraryItems()
            }
        }
    }

    private fun loadLocalPlaylistsThenSync() {
        loadLocalPlaylistsInternal()
        syncPlaylistsWithBackend()
    }

    private fun syncPlaylistsWithBackend() {
        if (!ApiClient.isLoggedIn()) return

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
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
