package com.spotifyclone.app.ui.playlist

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.spotifyclone.app.R
import com.spotifyclone.app.SpotifyApplication
import com.spotifyclone.app.api.ApiClient
import com.spotifyclone.app.databinding.FragmentPlaylistDetailBinding
import com.spotifyclone.app.model.PlaylistRequest
import com.spotifyclone.app.model.TrackDto
import com.spotifyclone.app.ui.adapter.TrackAdapter
import com.spotifyclone.app.ui.dialog.CustomDialogHelper
import com.spotifyclone.app.ui.dialog.TrackOptionsHelper
import com.spotifyclone.app.ui.search.SearchFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    private val pickMediaLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val uriString = it.toString()
            pendingChosenCoverUri = uriString
            pendingCoverDialogImageView?.let { img ->
                Glide.with(this).load(it).centerCrop().into(img)
            }
        }
    }

    companion object {
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
        setupRecyclerView()
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
    }

    private fun renderCoverImage() {
        val cover = playlistCoverUrl ?: playlistTracks.firstOrNull()?.thumbnailUrl
        if (!cover.isNullOrEmpty()) {
            binding.imgPlaylistCover.imageTintList = null
            Glide.with(this)
                .load(cover)
                .placeholder(R.drawable.ic_library)
                .error(R.drawable.ic_library)
                .centerCrop()
                .into(binding.imgPlaylistCover)
        } else {
            binding.imgPlaylistCover.setImageResource(R.drawable.ic_library)
            binding.imgPlaylistCover.setColorFilter(requireContext().getColor(R.color.spotify_green))
        }
    }

    private fun setupRecyclerView() {
        trackAdapter = TrackAdapter(
            tracks = playlistTracks,
            onTrackClick = { _, position ->
                // Play ISOLATED playlist queue: only songs from this playlist play next
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

    private fun setupListeners() {
        binding.btnBackPlaylistDetail.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        // Change Cover (GIF or Image)
        binding.btnChangeCoverBadge.setOnClickListener {
            showEditPlaylistDialog()
        }
        binding.imgPlaylistCover.setOnClickListener {
            showEditPlaylistDialog()
        }

        // Play Button: Starts playback strictly isolated within this playlist
        binding.btnPlaylistPlayFab.setOnClickListener {
            if (playlistTracks.isNotEmpty()) {
                SpotifyApplication.instance.playbackManager.playPlaylistQueue(playlistTracks, 0)
            } else {
                Toast.makeText(context, "Playlist is empty", Toast.LENGTH_SHORT).show()
            }
        }

        // Shuffle Button: Smart shuffle inside this playlist
        binding.btnPlaylistShuffle.setOnClickListener {
            if (playlistTracks.isNotEmpty()) {
                SpotifyApplication.instance.playbackManager.setShuffle(true)
                val randomIdx = playlistTracks.indices.random()
                SpotifyApplication.instance.playbackManager.playPlaylistQueue(playlistTracks, randomIdx)
                Toast.makeText(context, "Shuffling \"$playlistTitle\"", Toast.LENGTH_SHORT).show()
            }
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

        // 3-dots Options Menu
        binding.btnPlaylistOptions.setOnClickListener {
            showPlaylistOptionsMenu()
        }

        // Empty state search button
        binding.btnFindSongsToAdd.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, SearchFragment())
                .addToBackStack(null)
                .commit()
        }
    }

    private fun observePlaylistTracks() {
        lifecycleScope.launch {
            val db = SpotifyApplication.instance.database
            // Find localId
            val local = withContext(Dispatchers.IO) {
                db.playlistDao().getPlaylistByRemoteId(playlistId)
                    ?: db.playlistDao().getPlaylistById(playlistId)
                    ?: db.playlistDao().getPlaylistByTitle(playlistTitle)
            }

            val targetId = local?.id ?: playlistId

            db.trackDao().getTracksForPlaylist(targetId).collectLatest { localTracks ->
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
                playlistTracks.addAll(dtos)
                trackAdapter.updateTracks(playlistTracks)

                // Update Stats
                val totalSeconds = playlistTracks.mapNotNull { it.durationSeconds }.sum()
                val minutes = totalSeconds / 60
                val count = playlistTracks.size
                binding.textPlaylistStats.text = "Playlist • $count ${if (count == 1) "song" else "songs"} • $minutes min"

                if (count == 0) {
                    binding.layoutPlaylistEmpty.visibility = View.VISIBLE
                    binding.recyclerPlaylistTracks.visibility = View.GONE
                } else {
                    binding.layoutPlaylistEmpty.visibility = View.GONE
                    binding.recyclerPlaylistTracks.visibility = View.VISIBLE
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

    private fun showPlaylistOptionsMenu() {
        val options = arrayOf("Edit Details & Cover (GIF/Image)", "Delete Playlist")
        AlertDialog.Builder(requireContext())
            .setTitle(playlistTitle)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showEditPlaylistDialog()
                    1 -> deleteThisPlaylist()
                }
            }
            .show()
    }

    private fun showEditPlaylistDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_custom_playlist_editor, null)
        val textTitle = view.findViewById<TextView>(R.id.text_editor_dialog_title)
        val imgPreview = view.findViewById<ImageView>(R.id.img_editor_cover_preview)
        val btnPickFile = view.findViewById<TextView>(R.id.btn_pick_cover_file)
        val editCoverUrl = view.findViewById<android.widget.EditText>(R.id.edit_playlist_cover_url)
        val editTitle = view.findViewById<android.widget.EditText>(R.id.edit_playlist_title)
        val editDesc = view.findViewById<android.widget.EditText>(R.id.edit_playlist_description)
        val btnCancel = view.findViewById<TextView>(R.id.btn_cancel_playlist_editor)
        val btnSave = view.findViewById<TextView>(R.id.btn_save_playlist_editor)

        textTitle.text = "Edit Playlist"
        editTitle.setText(playlistTitle)
        editDesc.setText(playlistDescription ?: "")
        editCoverUrl.setText(playlistCoverUrl ?: "")

        pendingCoverDialogImageView = imgPreview
        pendingChosenCoverUri = playlistCoverUrl

        if (!playlistCoverUrl.isNullOrEmpty()) {
            Glide.with(this).load(playlistCoverUrl).centerCrop().into(imgPreview)
        }

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
            val newTitle = editTitle.text.toString().trim()
            val newDesc = editDesc.text.toString().trim().takeIf { it.isNotBlank() }
            val urlText = editCoverUrl.text.toString().trim().takeIf { it.isNotBlank() }
            val newCover = pendingChosenCoverUri ?: urlText

            if (newTitle.isEmpty()) {
                Toast.makeText(context, "Title cannot be empty", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            playlistTitle = newTitle
            playlistDescription = newDesc
            playlistCoverUrl = newCover
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
