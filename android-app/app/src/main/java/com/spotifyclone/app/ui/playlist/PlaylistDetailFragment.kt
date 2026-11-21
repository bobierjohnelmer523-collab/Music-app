package com.spotifyclone.app.ui.playlist

import android.content.Intent
import android.graphics.RenderEffect
import android.graphics.Shader
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.content.Context
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.spotifyclone.app.R
import com.spotifyclone.app.SpotifyApplication
import com.spotifyclone.app.api.ApiClient
import com.spotifyclone.app.data.local.entities.LocalTrack
import com.spotifyclone.app.data.local.entities.LocalPlaylist
import com.spotifyclone.app.databinding.FragmentPlaylistDetailBinding
import com.spotifyclone.app.extractor.NewPipeExtractorHelper
import com.spotifyclone.app.model.PlaylistRequest
import com.spotifyclone.app.model.TrackDto
import com.spotifyclone.app.ui.adapter.TrackAdapter
import com.spotifyclone.app.ui.adapter.PlaylistAddTrackAdapter
import com.spotifyclone.app.ui.dialog.CustomDialogHelper
import com.spotifyclone.app.ui.dialog.TrackOptionsHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.widget.addTextChangedListener

class PlaylistDetailFragment : Fragment() {

    private var _binding: FragmentPlaylistDetailBinding? = null
    private val binding get() = _binding!!

    private var playlistId: Long = 0L
    private var playlistTitle: String = ""
    private var playlistDescription: String? = null
    private var playlistCoverUrl: String? = null

    private val playlistTracks = mutableListOf<TrackDto>()
    private lateinit var trackAdapter: TrackAdapter

    // Photo/GIF Picker
    private var pendingCoverDialogImageView: ImageView? = null
    private var pendingChosenCoverUri: String? = null
    private var pendingBackgroundDialogImageView: ImageView? = null
    private var pendingChosenBackgroundUri: String? = null
    private var searchJob: Job? = null
    private var playlistSortMode = SORT_PLAYLIST_ORDER

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
            pendingChosenBackgroundUri = it.toString()
            pendingBackgroundDialogImageView?.let { image ->
                Glide.with(this).load(it).centerCrop().into(image)
            }
        }
    }

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
            pendingChosenCoverUri = uriString
            pendingCoverDialogImageView?.let { img ->
                img.imageTintList = null
                img.clearColorFilter()
                Glide.with(this).load(it).centerCrop().into(img)
            }
        }
    }

    companion object {
        private const val SORT_PLAYLIST_ORDER = "playlist_order"
        private const val SORT_RECENTLY_ADDED = "recently_added"
        private const val SORT_TITLE = "title_az"
        private const val SORT_MOST_PLAYED = "most_played"
        private const val PREFS_PLAYLIST_DETAIL = "playlist_detail_preferences"
        private const val ARG_PLAYLIST_ID = "arg_playlist_id"
        private const val ARG_TITLE = "arg_title"
        private const val ARG_DESCRIPTION = "arg_description"
        private const val ARG_COVER_URL = "arg_cover_url"

        fun newInstance(id: Long, title: String, description: String?, coverUrl: String?): PlaylistDetailFragment {
            val fragment = PlaylistDetailFragment()
            val args = Bundle().apply {
                putLong(ARG_PLAYLIST_ID, id)
                putString(ARG_TITLE, title)
                putString(ARG_DESCRIPTION, description)
                putString(ARG_COVER_URL, coverUrl)
            }
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            playlistId = it.getLong(ARG_PLAYLIST_ID)
            playlistTitle = it.getString(ARG_TITLE, "")
            playlistDescription = it.getString(ARG_DESCRIPTION)
            playlistCoverUrl = it.getString(ARG_COVER_URL)
            playlistSortMode = playlistPrefs().getString(sortPreferenceKey(), SORT_PLAYLIST_ORDER)
                ?: SORT_PLAYLIST_ORDER
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlaylistDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupUI()
        applyPlaylistBackdropEffect()
        binding.layoutPlaylistHeaderContent.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            updatePlaylistBackdropHeight(bottom - top)
        }
        setupRecyclerView()
        observePlaybackUi()
        observePlaylistTracks()
        setupListeners()
    }

    private fun setupUI() {
        binding.textPlaylistTitle.text = playlistTitle
        if (!playlistDescription.isNullOrBlank()) {
            binding.textPlaylistDescription.text = playlistDescription
            binding.textPlaylistDescription.visibility = View.VISIBLE
        } else {
            binding.textPlaylistDescription.visibility = View.GONE
        }

        renderCoverImage()
        binding.btnPlaylistOptions.visibility = View.GONE
    }

    private fun renderCoverImage() {
        val cover = playlistCoverUrl ?: playlistTracks.firstOrNull()?.thumbnailUrl
        if (!cover.isNullOrEmpty()) {
            binding.imgPlaylistCover.imageTintList = null
            Glide.with(this)
                .load(cover)
                .placeholder(R.drawable.ic_library)
                .error(R.drawable.ic_library)
                .override(720, 720)
                .centerCrop()
                .into(binding.imgPlaylistCover)
        } else {
            binding.imgPlaylistCover.setImageResource(R.drawable.ic_library)
            binding.imgPlaylistCover.setColorFilter(requireContext().getColor(R.color.spotify_green))
        }

        val backgroundUrl = playlistPrefs().getString(backgroundPreferenceKey(), null)
        if (!backgroundUrl.isNullOrBlank()) {
            binding.imgPlaylistBackground.visibility = View.VISIBLE
            Glide.with(this)
                .load(backgroundUrl)
                .transition(DrawableTransitionOptions.withCrossFade(450))
                .centerCrop()
                .into(binding.imgPlaylistBackground)
        } else {
            binding.imgPlaylistBackground.setImageDrawable(null)
            binding.imgPlaylistBackground.visibility = View.GONE
        }
    }

    private fun updatePlaylistBackdropHeight(headerHeight: Int) {
        if (headerHeight <= 0) return
        val backgroundParams = binding.imgPlaylistBackground.layoutParams
        if (backgroundParams.height != headerHeight) {
            backgroundParams.height = headerHeight
            binding.imgPlaylistBackground.layoutParams = backgroundParams
        }
        val gradientParams = binding.viewPlaylistBackgroundGradient.layoutParams
        if (gradientParams.height != headerHeight) {
            gradientParams.height = headerHeight
            binding.viewPlaylistBackgroundGradient.layoutParams = gradientParams
        }
    }

    private fun applyPlaylistBackdropEffect() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            binding.imgPlaylistBackground.setRenderEffect(
                RenderEffect.createBlurEffect(20f, 20f, Shader.TileMode.CLAMP)
            )
        }
    }

    private fun playlistPrefs() =
        requireContext().getSharedPreferences(PREFS_PLAYLIST_DETAIL, Context.MODE_PRIVATE)

    private fun backgroundPreferenceKey() = "playlist_background_$playlistId"

    private fun sortPreferenceKey() = "playlist_sort_$playlistId"

    private fun playCountPreferenceKey(youtubeId: String) = "playlist_plays_${playlistId}_$youtubeId"

    private fun incrementPlayCount(track: TrackDto) {
        val key = playCountPreferenceKey(track.youtubeId)
        playlistPrefs().edit().putInt(key, playlistPrefs().getInt(key, 0) + 1).apply()
    }

    private fun sortTracks(tracks: List<TrackDto>): List<TrackDto> = when (playlistSortMode) {
        SORT_RECENTLY_ADDED -> tracks.sortedByDescending { it.position ?: 0 }
        SORT_TITLE -> tracks.sortedBy { it.title.lowercase() }
        SORT_MOST_PLAYED -> tracks.sortedWith(
            compareByDescending<TrackDto> {
                playlistPrefs().getInt(playCountPreferenceKey(it.youtubeId), 0)
            }.thenByDescending { it.position ?: 0 }
        )
        else -> tracks.sortedBy { it.position ?: 0 }
    }

    private fun showSortPlaylistMenu() {
        val labels = arrayOf("Playlist order", "Recently added", "Title (A–Z)", "Most played")
        AlertDialog.Builder(requireContext())
            .setTitle("Sort songs")
            .setItems(labels) { _, which ->
                playlistSortMode = when (which) {
                    1 -> SORT_RECENTLY_ADDED
                    2 -> SORT_TITLE
                    3 -> SORT_MOST_PLAYED
                    else -> SORT_PLAYLIST_ORDER
                }
                playlistPrefs().edit().putString(sortPreferenceKey(), playlistSortMode).apply()
                val sorted = sortTracks(playlistTracks)
                playlistTracks.clear()
                playlistTracks.addAll(sorted)
                trackAdapter.updateTracks(playlistTracks)
            }
            .show()
    }

    private fun showAddTracksBottomSheet() {
        val sheet = BottomSheetDialog(requireContext())
        val content = layoutInflater.inflate(R.layout.sheet_add_playlist_tracks, null)
        val resultsView = content.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recycler_add_track_results)
        val searchField = content.findViewById<android.widget.EditText>(R.id.edit_add_track_search)
        val closeButton = content.findViewById<View>(R.id.btn_close_add_tracks)
        content.findViewById<TextView>(R.id.text_add_to_playlist_name).text = playlistTitle

        val adapter = PlaylistAddTrackAdapter { track, onAdded ->
            addTrackToPlaylist(track, onAdded)
        }
        resultsView.layoutManager = LinearLayoutManager(requireContext())
        resultsView.adapter = adapter

        searchField.addTextChangedListener { text ->
            searchForPlaylistTracks(text?.toString().orEmpty(), adapter, sheet) {
                searchField.text?.toString().orEmpty()
            }
        }
        searchField.setOnEditorActionListener { _, actionId, event ->
            val isSearch = actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (isSearch) {
                searchJob?.cancel()
                searchForPlaylistTracks(searchField.text.toString(), adapter, sheet, debounce = false) {
                    searchField.text?.toString().orEmpty()
                }
            }
            isSearch
        }
        closeButton.setOnClickListener { sheet.dismiss() }

        sheet.setContentView(content)
        sheet.setOnDismissListener { searchJob?.cancel() }
        sheet.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        sheet.setOnShowListener {
            sheet.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { bottomSheet ->
                bottomSheet.layoutParams = bottomSheet.layoutParams.apply {
                    height = ViewGroup.LayoutParams.MATCH_PARENT
                }
            }
            sheet.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            sheet.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            sheet.behavior.skipCollapsed = true
        }
        sheet.show()
    }

    private fun searchForPlaylistTracks(
        query: String,
        adapter: PlaylistAddTrackAdapter,
        sheet: BottomSheetDialog,
        debounce: Boolean = true,
        currentQuery: () -> String
    ) {
        searchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.length < 2) {
            adapter.submitTracks(emptyList())
            return
        }
        searchJob = viewLifecycleOwner.lifecycleScope.launch {
            if (debounce) delay(180)
            val result = withContext(Dispatchers.IO) {
                NewPipeExtractorHelper.searchYouTubeMusic(trimmed)
            }
            if (!sheet.isShowing || trimmed != currentQuery().trim()) return@launch
            val existingIds = playlistTracks.mapTo(mutableSetOf()) { it.youtubeId }
            adapter.submitTracks(
                result.getOrDefault(emptyList()).filter { it.youtubeId !in existingIds }
            )
        }
    }

    private fun addTrackToPlaylist(track: TrackDto, onAdded: (Boolean) -> Unit) {
        // Keep the database write alive if the bottom sheet or fragment view is
        // dismissed while the write is in progress. The old code also performed
        // the backend request before notifying the sheet, tying the UI callback
        // to a slow or failing network request.
        lifecycleScope.launch {
            var remotePlaylistId: Long? = null
            var savedLocally = false
            try {
                val added = withContext(Dispatchers.IO) {
                    val db = SpotifyApplication.instance.database
                    val matchedPlaylist = db.playlistDao().getPlaylistByTitle(playlistTitle)
                        ?: db.playlistDao().getPlaylistByRemoteId(playlistId)
                        ?: db.playlistDao().getPlaylistById(playlistId)
                    val local = matchedPlaylist ?: run {
                        val insertedId = db.playlistDao().insert(
                            LocalPlaylist(
                                remoteId = playlistId.takeIf { ApiClient.isLoggedIn() },
                                title = playlistTitle,
                                description = playlistDescription,
                                coverUrl = playlistCoverUrl,
                                isOfflineOnly = !ApiClient.isLoggedIn()
                            )
                        )
                        db.playlistDao().getPlaylistById(insertedId)
                    } ?: error("Playlist is no longer available")
                    remotePlaylistId = local.remoteId

                    val existing = db.trackDao().getTracksForPlaylistSync(local.id)
                    if (existing.any { it.youtubeId == track.youtubeId }) return@withContext false

                    val nextPosition = (existing.maxOfOrNull { it.position } ?: -1) + 1
                    db.trackDao().insert(
                        LocalTrack(
                            playlistId = local.id,
                            youtubeId = track.youtubeId,
                            title = track.title,
                            artist = track.artist,
                            durationSeconds = track.durationSeconds,
                            thumbnailUrl = track.thumbnailUrl,
                            position = nextPosition
                        )
                    )
                    db.playlistDao().update(local.copy(updatedAt = System.currentTimeMillis()))
                    savedLocally = true
                    true
                }
                onAdded(added)
                Toast.makeText(
                    context,
                    if (added) "Added to $playlistTitle" else "This song is already in the playlist",
                    Toast.LENGTH_SHORT
                ).show()
                if (added && ApiClient.isLoggedIn() && remotePlaylistId != null) {
                    // Sync remotely after the local playlist and its UI are
                    // already updated. A network error must not close the sheet
                    // or undo a successful local add.
                    launch(Dispatchers.IO) {
                        runCatching {
                            ApiClient.getApi().addTrackToPlaylist(remotePlaylistId!!, track)
                        }
                    }
                }
            } catch (error: Exception) {
                // If a later step failed after Room committed the track, treat
                // the action as successful so the Add button doesn't retry it.
                onAdded(savedLocally)
                Toast.makeText(context, "Couldn't add song: ${error.localizedMessage ?: "Please try again"}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setupRecyclerView() {
        trackAdapter = TrackAdapter(
            tracks = playlistTracks,
            onTrackClick = { _, position ->
                // Play ISOLATED playlist queue: only songs from this playlist play next
                playlistTracks.getOrNull(position)?.let(::incrementPlayCount)
                SpotifyApplication.instance.playbackManager.playPlaylistQueue(playlistTracks, position)
            },
            onMenuClick = { track ->
                showTrackMenu(track)
            }
        )

        binding.recyclerPlaylistTracks.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = trackAdapter
        }
    }

    private fun observePlaybackUi() {
        val playback = SpotifyApplication.instance.playbackManager
        viewLifecycleOwner.lifecycleScope.launch {
            combine(playback.currentTrack, playback.isPlaying) { track, playing -> track to playing }
                .collectLatest { (track, playing) ->
                    updatePlayButton(track, playing)
                }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            playback.isShuffleEnabled.collectLatest { enabled ->
                binding.btnPlaylistShuffle.imageTintList = android.content.res.ColorStateList.valueOf(
                    requireContext().getColor(
                        if (enabled) R.color.spotify_green else R.color.spotify_light_grey
                    )
                )
            }
        }
    }

    private fun updatePlayButton(track: TrackDto?, playing: Boolean) {
        val isThisPlaylist = track != null && playlistTracks.any { playlistTrack ->
            (track.youtubeId.isNotBlank() && playlistTrack.youtubeId == track.youtubeId) ||
                (playlistTrack.title.equals(track.title, ignoreCase = true) &&
                    playlistTrack.artist.equals(track.artist, ignoreCase = true))
        }
        binding.imgPlayFabIcon.setImageResource(
            if (isThisPlaylist && playing) R.drawable.ic_pause else R.drawable.ic_play
        )
        binding.btnPlaylistPlayFab.contentDescription =
            if (isThisPlaylist && playing) "Pause playlist" else "Play playlist"
    }

    private fun setupListeners() {
        binding.btnBackPlaylistDetail.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        binding.imgPlaylistCover.setOnClickListener {
            showEditPlaylistDialog()
        }

        // Play Button: Starts playback strictly isolated within this playlist
        binding.btnPlaylistPlayFab.setOnClickListener {
            if (playlistTracks.isNotEmpty()) {
                val playback = SpotifyApplication.instance.playbackManager
                val current = playback.currentTrack.value
                val currentIsInPlaylist = current != null && playlistTracks.any { playlistTrack ->
                    (current.youtubeId.isNotBlank() && playlistTrack.youtubeId == current.youtubeId) ||
                        (playlistTrack.title.equals(current.title, ignoreCase = true) &&
                            playlistTrack.artist.equals(current.artist, ignoreCase = true))
                }
                if (currentIsInPlaylist) {
                    playback.togglePlayPause()
                } else {
                    val startIndex = if (playback.isShuffleEnabled.value) {
                        playlistTracks.indices.random()
                    } else {
                        0
                    }
                    playlistTracks.getOrNull(startIndex)?.let(::incrementPlayCount)
                    playback.playPlaylistQueue(playlistTracks, startIndex)
                }
            } else {
                Toast.makeText(context, "Playlist is empty", Toast.LENGTH_SHORT).show()
            }
        }

        // Shuffle Button: Smart shuffle inside this playlist
        binding.btnPlaylistShuffle.setOnClickListener {
            val playback = SpotifyApplication.instance.playbackManager
            playback.setShuffle(!playback.isShuffleEnabled.value)
        }

        // Batch Download all tracks for offline listening
        binding.btnPlaylistDownloadAll.setOnClickListener {
            if (playlistTracks.isNotEmpty()) {
                Toast.makeText(
                    context,
                    "Downloading \"$playlistTitle\" (${playlistTracks.size} songs) for offline...",
                    Toast.LENGTH_LONG
                ).show()
                for (track in playlistTracks) {
                    SpotifyApplication.instance.downloadHelper.downloadTrack(track)
                }
            } else {
                Toast.makeText(context, "No songs to download", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnAddPlaylistTracks.setOnClickListener { showAddTracksBottomSheet() }
        binding.btnEditPlaylist.setOnClickListener { showEditPlaylistDialog() }
        binding.btnSortPlaylist.setOnClickListener { showSortPlaylistMenu() }

        // Empty state search button
        binding.btnFindSongsToAdd.setOnClickListener {
            showAddTracksBottomSheet()
        }
    }

    private fun observePlaylistTracks() {
        viewLifecycleOwner.lifecycleScope.launch {
            val db = SpotifyApplication.instance.database
            // Find localId
            val local = withContext(Dispatchers.IO) {
                db.playlistDao().getPlaylistByRemoteId(playlistId)
                    ?: db.playlistDao().getPlaylistById(playlistId)
                    ?: db.playlistDao().getPlaylistByTitle(playlistTitle)
            }

            val targetId = local?.id ?: playlistId

            db.trackDao().getTracksForPlaylist(targetId).collectLatest { localTracks ->
                val currentBinding = _binding ?: return@collectLatest
                val dtos = localTracks.map { t ->
                    TrackDto(
                        id = t.id,
                        youtubeId = t.youtubeId,
                        title = t.title,
                        artist = t.artist,
                        durationSeconds = t.durationSeconds,
                        thumbnailUrl = t.thumbnailUrl,
                        position = t.position
                    )
                }

                playlistTracks.clear()
                playlistTracks.addAll(sortTracks(dtos))
                trackAdapter.updateTracks(playlistTracks)
                val playback = SpotifyApplication.instance.playbackManager
                updatePlayButton(playback.currentTrack.value, playback.isPlaying.value)

                // Update Stats
                val totalSeconds = playlistTracks.mapNotNull { it.durationSeconds }.sum()
                val minutes = totalSeconds / 60
                val count = playlistTracks.size
                currentBinding.textPlaylistStats.text = "Playlist • $count ${if (count == 1) "song" else "songs"} • $minutes min"

                if (count == 0) {
                    currentBinding.layoutPlaylistEmpty.visibility = View.VISIBLE
                    currentBinding.recyclerPlaylistTracks.visibility = View.GONE
                } else {
                    currentBinding.layoutPlaylistEmpty.visibility = View.GONE
                    currentBinding.recyclerPlaylistTracks.visibility = View.VISIBLE
                }

                renderCoverImage()
            }
        }
    }

    private fun showTrackMenu(track: TrackDto) {
        val options = arrayOf(
            "Remove from this playlist",
            "Add to other playlist",
            "Share song",
            "Download offline",
            "Song credits"
        )

        AlertDialog.Builder(requireContext())
            .setTitle(track.title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> removeTrackFromPlaylist(track)
                    1 -> TrackOptionsHelper.showAddToPlaylistDialog(requireContext(), track)
                    2 -> TrackOptionsHelper.showTrackOptions(requireContext(), track)
                    3 -> SpotifyApplication.instance.downloadHelper.downloadTrack(track)
                    4 -> TrackOptionsHelper.showTrackOptions(requireContext(), track)
                }
            }
            .show()
    }

    private fun removeTrackFromPlaylist(track: TrackDto) {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = SpotifyApplication.instance.database
            val local = db.playlistDao().getPlaylistByRemoteId(playlistId)
                ?: db.playlistDao().getPlaylistById(playlistId)
                ?: db.playlistDao().getPlaylistByTitle(playlistTitle)

            val localId = local?.id ?: playlistId

            if (track.id != null) {
                db.trackDao().delete(
                    com.spotifyclone.app.data.local.entities.LocalTrack(
                        id = track.id,
                        playlistId = localId,
                        youtubeId = track.youtubeId,
                        title = track.title,
                        artist = track.artist,
                        durationSeconds = track.durationSeconds,
                        thumbnailUrl = track.thumbnailUrl,
                        position = track.position ?: 0
                    )
                )
            } else {
                val allTracks = db.trackDao().getTracksForPlaylistSync(localId)
                val target = allTracks.find { it.youtubeId == track.youtubeId }
                if (target != null) {
                    db.trackDao().delete(target)
                }
            }

            if (ApiClient.isLoggedIn() && local?.remoteId != null && track.id != null) {
                try {
                    ApiClient.getApi().removeTrackFromPlaylist(local.remoteId, track.id)
                } catch (_: Exception) {}
            }

            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Removed from \"$playlistTitle\"", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showEditPlaylistDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_custom_playlist_editor, null)
        val textTitle = view.findViewById<TextView>(R.id.text_editor_dialog_title)
        val imgPreview = view.findViewById<ImageView>(R.id.img_editor_cover_preview)
        val imgBackgroundPreview = view.findViewById<ImageView>(R.id.img_editor_background_preview)
        val btnPickFile = view.findViewById<TextView>(R.id.btn_pick_cover_file)
        val btnPickBackground = view.findViewById<TextView>(R.id.btn_pick_background_file)
        val editCoverUrl = view.findViewById<android.widget.EditText>(R.id.edit_playlist_cover_url)
        val editBackgroundUrl = view.findViewById<android.widget.EditText>(R.id.edit_playlist_background_url)
        val editTitle = view.findViewById<android.widget.EditText>(R.id.edit_playlist_title)
        val editDesc = view.findViewById<android.widget.EditText>(R.id.edit_playlist_description)
        val btnCancel = view.findViewById<TextView>(R.id.btn_cancel_playlist_editor)
        val btnSave = view.findViewById<TextView>(R.id.btn_save_playlist_editor)
        val btnDelete = view.findViewById<TextView>(R.id.btn_delete_playlist)
        val layoutDelete = view.findViewById<View>(R.id.layout_editor_delete)

        textTitle.text = "Edit Playlist"
        editTitle.setText(playlistTitle)
        editDesc.setText(playlistDescription ?: "")
        editCoverUrl.setText(playlistCoverUrl ?: "")
        val currentBackground = playlistPrefs().getString(backgroundPreferenceKey(), null)
        editBackgroundUrl.setText(currentBackground ?: "")
        layoutDelete.visibility = View.VISIBLE

        pendingCoverDialogImageView = imgPreview
        pendingChosenCoverUri = null
        pendingBackgroundDialogImageView = imgBackgroundPreview
        pendingChosenBackgroundUri = null

        if (!playlistCoverUrl.isNullOrEmpty()) {
            imgPreview.imageTintList = null
            imgPreview.clearColorFilter()
            Glide.with(this).load(playlistCoverUrl).centerCrop().into(imgPreview)
        }
        if (!currentBackground.isNullOrEmpty()) {
            Glide.with(this).load(currentBackground).centerCrop().into(imgBackgroundPreview)
        }

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
        btnDelete.setOnClickListener {
            dialog.dismiss()
            deleteThisPlaylist()
        }

        btnSave.setOnClickListener {
            val newTitle = editTitle.text.toString().trim()
            val newDesc = editDesc.text.toString().trim().takeIf { it.isNotBlank() }
            val urlText = editCoverUrl.text.toString().trim().takeIf { it.isNotBlank() }
            val newCover = pendingChosenCoverUri ?: urlText ?: playlistCoverUrl
            val backgroundText = editBackgroundUrl.text.toString().trim().takeIf { it.isNotBlank() }
            val newBackground = pendingChosenBackgroundUri ?: backgroundText ?: currentBackground

            if (newTitle.isEmpty()) {
                Toast.makeText(context, "Title cannot be empty", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            playlistTitle = newTitle
            playlistDescription = newDesc
            playlistCoverUrl = newCover
            if (newBackground.isNullOrBlank()) {
                playlistPrefs().edit().remove(backgroundPreferenceKey()).apply()
            } else {
                playlistPrefs().edit().putString(backgroundPreferenceKey(), newBackground).apply()
            }
            setupUI()

            lifecycleScope.launch(Dispatchers.IO) {
                val db = SpotifyApplication.instance.database
                val local = db.playlistDao().getPlaylistByRemoteId(playlistId)
                    ?: db.playlistDao().getPlaylistById(playlistId)
                    ?: db.playlistDao().getPlaylistByTitle(playlistTitle)

                val targetId = local?.id ?: playlistId
                db.playlistDao().updatePlaylistDetails(targetId, newTitle, newDesc, newCover)

                if (ApiClient.isLoggedIn() && local?.remoteId != null) {
                    try {
                        ApiClient.getApi().updatePlaylist(
                            local.remoteId,
                            PlaylistRequest(title = newTitle, description = newDesc, coverUrl = newCover)
                        )
                    } catch (_: Exception) {}
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Playlist updated", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
            }
        }

        dialog.show()
    }

    private fun deleteThisPlaylist() {
        CustomDialogHelper.showConfirmDialog(
            context = requireContext(),
            title = "Delete Playlist",
            message = "Are you sure you want to delete \"$playlistTitle\"?",
            confirmText = "Delete",
            isDestructive = true
        ) {
            lifecycleScope.launch(Dispatchers.IO) {
                val db = SpotifyApplication.instance.database
                val local = db.playlistDao().getPlaylistByRemoteId(playlistId)
                    ?: db.playlistDao().getPlaylistById(playlistId)
                    ?: db.playlistDao().getPlaylistByTitle(playlistTitle)

                val remoteId = local?.remoteId ?: playlistId
                if (ApiClient.isLoggedIn() && remoteId > 0) {
                    try {
                        ApiClient.getApi().deletePlaylist(remoteId)
                    } catch (_: Exception) {}
                }

                if (local != null) {
                    db.trackDao().deleteTracksForPlaylist(local.id)
                    db.playlistDao().delete(local)
                } else {
                    db.playlistDao().deleteByIdOrRemoteIdOrTitle(playlistId, playlistTitle)
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Playlist deleted", Toast.LENGTH_SHORT).show()
                    parentFragmentManager.popBackStack()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
