package com.spotifyclone.app.ui.downloads

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.spotifyclone.app.R
import com.spotifyclone.app.SpotifyApplication
import com.spotifyclone.app.data.local.entities.OfflineFolder
import com.spotifyclone.app.data.local.entities.OfflineSongFolder
import com.spotifyclone.app.databinding.FragmentDownloadsBinding
import com.spotifyclone.app.model.TrackDto
import com.spotifyclone.app.ui.adapter.DownloadItem
import com.spotifyclone.app.ui.adapter.DownloadedTrackAdapter
import com.spotifyclone.app.ui.dialog.CustomDialogHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DownloadsFragment : Fragment() {

    private var _binding: FragmentDownloadsBinding? = null
    private val binding get() = _binding!!

    private lateinit var downloadAdapter: DownloadedTrackAdapter
    private val allDisplayItems = mutableListOf<DownloadItem>()
    private val filteredDisplayItems = mutableListOf<DownloadItem>()

    private var currentSelectedFolder: String = "All"
    private val folderNames = mutableListOf<String>()
    private val folderTrackIds = mutableSetOf<String>()

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
        observeFolders()
        observeDownloadedAndActiveTracks()
    }

    private fun setupRecyclerView() {
        downloadAdapter = DownloadedTrackAdapter(
            tracks = filteredDisplayItems,
            onTrackClick = { item, _ ->
                val completed = filteredDisplayItems.filter { !it.isDownloading }
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
            },
            onSelectionChanged = { selectedCount ->
                if (selectedCount > 0) {
                    binding.cardSelectBar.visibility = View.VISIBLE
                    binding.textSelectCount.text = "$selectedCount selected"
                } else {
                    binding.cardSelectBar.visibility = View.GONE
                }
            }
        )

        binding.recyclerDownloads.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = downloadAdapter
        }
    }

    private fun setupListeners() {
        binding.btnBackDownloads.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        binding.btnCreateOfflinePlaylist.setOnClickListener {
            showCreateFolderDialog()
        }

        binding.btnCancelSelect.setOnClickListener {
            downloadAdapter.setSelectionMode(false)
            binding.cardSelectBar.visibility = View.GONE
        }

        binding.btnMoveToFolder.setOnClickListener {
            showFolderPickerForSelectedTracks()
        }
    }

    private fun showCreateFolderDialog(onFolderCreated: ((String) -> Unit)? = null) {
        CustomDialogHelper.showInputDialog(
            context = requireContext(),
            title = "New Offline Folder",
            message = "Create a folder inside Downloaded Songs to organize your offline music.",
            hint = "Folder Name",
            confirmText = "Create"
        ) { name ->
            lifecycleScope.launch(Dispatchers.IO) {
                SpotifyApplication.instance.database.offlineFolderDao().insertFolder(
                    OfflineFolder(name = name)
                )
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Folder \"$name\" created", Toast.LENGTH_SHORT).show()
                    onFolderCreated?.invoke(name)
                }
            }
        }
    }

    private fun showDeleteDialog(item: DownloadItem) {
        val title = if (item.isDownloading) "Cancel Download" else "Delete Download"
        val message = if (item.isDownloading) "Cancel download for \"${item.title}\"?" else "Delete \"${item.title}\" from offline storage?"

        CustomDialogHelper.showConfirmDialog(
            context = requireContext(),
            title = title,
            message = message,
            confirmText = if (item.isDownloading) "Cancel Download" else "Delete",
            isDestructive = true
        ) {
            SpotifyApplication.instance.downloadHelper.deleteDownloadedTrack(item.youtubeId) {
                activity?.runOnUiThread {
                    Toast.makeText(context, "Removed from offline downloads", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showFolderPickerForSelectedTracks() {
        val selectedIds = downloadAdapter.selectedIds.toList()
        if (selectedIds.isEmpty()) return

        val bottomSheet = BottomSheetDialog(requireContext())
        val view = layoutInflater.inflate(R.layout.dialog_custom_folder_picker, null)
        bottomSheet.setContentView(view)

        val btnCreate = view.findViewById<LinearLayout>(R.id.btn_create_new_folder_in_picker)
        val container = view.findViewById<LinearLayout>(R.id.layout_folder_list_container)

        btnCreate.setOnClickListener {
            bottomSheet.dismiss()
            showCreateFolderDialog { newFolderName ->
                assignSelectedTracksToFolder(selectedIds, newFolderName)
            }
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val folders = SpotifyApplication.instance.database.offlineFolderDao().getAllFoldersOnce()
            withContext(Dispatchers.Main) {
                container.removeAllViews()

                // Option: All / Uncategorized
                addFolderRow(container, "All (Remove from Folders)") {
                    bottomSheet.dismiss()
                    removeSelectedTracksFromFolders(selectedIds)
                }

                for (folder in folders) {
                    addFolderRow(container, folder.name) {
                        bottomSheet.dismiss()
                        assignSelectedTracksToFolder(selectedIds, folder.name)
                    }
                }
            }
        }

        bottomSheet.show()
    }

    private fun addFolderRow(container: LinearLayout, folderName: String, onClick: () -> Unit) {
        val row = LinearLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 120
            )
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(16, 0, 16, 0)
            setBackgroundResource(android.R.drawable.list_selector_background)
            setOnClickListener { onClick() }
        }

        val icon = ImageView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(54, 54)
            setImageResource(R.drawable.ic_library)
            setColorFilter(ContextCompat.getColor(context, R.color.spotify_light_grey))
        }

        val text = TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginStart = 32 }
            text = folderName
            setTextColor(ContextCompat.getColor(context, R.color.spotify_white))
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        row.addView(icon)
        row.addView(text)
        container.addView(row)
    }

    private fun assignSelectedTracksToFolder(trackIds: List<String>, folderName: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = SpotifyApplication.instance.database
            val items = trackIds.map { id ->
                OfflineSongFolder(folderName = folderName, youtubeId = id)
            }
            db.offlineFolderDao().addSongsToFolder(items)

            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Moved ${trackIds.size} songs to \"$folderName\"", Toast.LENGTH_SHORT).show()
                downloadAdapter.setSelectionMode(false)
                binding.cardSelectBar.visibility = View.GONE
            }
        }
    }

    private fun removeSelectedTracksFromFolders(trackIds: List<String>) {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = SpotifyApplication.instance.database
            for (id in trackIds) {
                for (f in folderNames) {
                    db.offlineFolderDao().removeSongFromFolder(f, id)
                }
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Songs removed from folders", Toast.LENGTH_SHORT).show()
                downloadAdapter.setSelectionMode(false)
                binding.cardSelectBar.visibility = View.GONE
            }
        }
    }

    private fun observeFolders() {
        lifecycleScope.launch {
            SpotifyApplication.instance.database.offlineFolderDao()
                .getAllFolders()
                .collectLatest { folders ->
                    folderNames.clear()
                    folderNames.addAll(folders.map { it.name })
                    renderFolderChips()
                }
        }
    }

    private fun renderFolderChips() {
        val container = binding.layoutFolderChips
        container.removeAllViews()

        val allChips = mutableListOf("All")
        allChips.addAll(folderNames)

        for (name in allChips) {
            val chip = TextView(requireContext()).apply {
                val isSelected = (name == currentSelectedFolder)
                text = name
                textSize = 12f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setPadding(32, 16, 32, 16)
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = 16
                }
                layoutParams = params

                if (isSelected) {
                    setBackgroundResource(R.drawable.chip_selected_bg)
                    setTextColor(ContextCompat.getColor(context, R.color.spotify_black))
                } else {
                    setBackgroundResource(R.drawable.chip_unselected_bg)
                    setTextColor(ContextCompat.getColor(context, R.color.spotify_white))
                }

                setOnClickListener {
                    currentSelectedFolder = name
                    renderFolderChips()
                    filterTracksForCurrentFolder()
                }
            }
            container.addView(chip)
        }
    }

    private fun filterTracksForCurrentFolder() {
        if (currentSelectedFolder == "All") {
            filteredDisplayItems.clear()
            filteredDisplayItems.addAll(allDisplayItems)
            downloadAdapter.updateTracks(filteredDisplayItems)
            updateEmptyState()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            SpotifyApplication.instance.database.offlineFolderDao()
                .getTrackIdsInFolder(currentSelectedFolder)
                .collectLatest { ids ->
                    folderTrackIds.clear()
                    folderTrackIds.addAll(ids)

                    withContext(Dispatchers.Main) {
                        filteredDisplayItems.clear()
                        filteredDisplayItems.addAll(allDisplayItems.filter {
                            it.isDownloading || folderTrackIds.contains(it.youtubeId)
                        })
                        downloadAdapter.updateTracks(filteredDisplayItems)
                        updateEmptyState()
                    }
                }
        }
    }

    private fun updateEmptyState() {
        if (filteredDisplayItems.isEmpty()) {
            binding.textEmptyDownloads.visibility = View.VISIBLE
        } else {
            binding.textEmptyDownloads.visibility = View.GONE
        }
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

                // 1. Add actively downloading tracks
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
                allDisplayItems.clear()
                allDisplayItems.addAll(items)
                filterTracksForCurrentFolder()

                val completedCount = items.count { !it.isDownloading }
                val downloadingCount = items.count { it.isDownloading }

                val statusText = if (downloadingCount > 0) {
                    "$completedCount offline • $downloadingCount downloading"
                } else {
                    "$completedCount offline songs ready for playback"
                }
                binding.textDownloadsSubtitle.text = statusText
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
